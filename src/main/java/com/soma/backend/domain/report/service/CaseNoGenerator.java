package com.soma.backend.domain.report.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 사람용 사건번호(`case_no`) 발급기 — {@code yyyyMMdd-XXXXXX} (예: {@code 20260914-K3F9XM}).
 *
 * <h2>왜 연번이 아니라 랜덤인가</h2>
 * 이전 형식은 {@code yyyyMMdd-NNN} 연번이라 gapless를 유지해야 했고, 그러려면 당일 카운터 행을
 * 트랜잭션 커밋까지 잠가야 했다(롤백 시 번호가 되돌아가야 구멍이 안 생기므로). 그 결과 같은 날짜의
 * 리포트 생성이 전부 직렬화돼 POST /reports의 처리량 상한이 락 보유 시간으로 정해졌다.
 * gapless와 무경합은 원리적으로 양립하지 않는다 — 사람이 읽고 부르는 식별자에 연번이 필수는 아니므로
 * 연번을 포기하고 락을 없앴다(이슈 #309).
 *
 * <h2>Crockford Base32를 쓰는 이유</h2>
 * 전화로 불러주고 손으로 받아적는 값이라 혼동 문자를 없애야 한다. 알파벳에서 {@code I}·{@code L}
 * ({@code 1}과 혼동)·{@code O}({@code 0}과 혼동)·{@code U}를 뺀 32자만 쓴다. 대문자로만 발급한다.
 *
 * <h2>충돌 처리</h2>
 * 6자리는 32^6 ≈ 10.7억 가지이고 날짜별로 네임스페이스가 나뉘므로 하루치만 경쟁한다. 그래도 0은
 * 아니라서 발급 직후 {@code existsByCaseNo}로 확인하고 중복이면 다시 뽑는다. 이 조회는 UNIQUE 인덱스를
 * 타는 단순 조회라 <b>행 락을 잡지 않는다</b> — 카운터를 없앤 목적이 유지된다.
 *
 * <p>확인과 INSERT 사이에는 경쟁 창이 남지만(다른 트랜잭션이 그 사이 같은 값을 커밋할 수 있다), 그
 * 확률은 충돌 확률에 다시 경쟁 창을 곱한 값이라 무시할 수준이고, 뚫리더라도 {@code reports.case_no}
 * UNIQUE 제약이 최종 방어선으로 막는다(500). 재시도를 이 메서드 안에서 무한정 돌리지 않는 이유도
 * 같다 — 여기서 다 막으려 하기보다 DB 제약에 최종 판정을 맡기는 편이 단순하다.
 */
@Component
@RequiredArgsConstructor
public class CaseNoGenerator {

  /** Crockford Base32 — I·L·O·U 제외(사람이 읽고 부르는 값이라 혼동 문자를 뺀다). */
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int CODE_LENGTH = 6;
  /** 중복 재시도 상한. 연속 실패 확률이 사실상 0이라 넘어가면 랜덤 소스 이상을 의심해야 한다. */
  private static final int MAX_ATTEMPTS = 5;

  private final ReportRepository reportRepository;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 오늘 날짜 기준으로 미사용 사건번호를 발급한다.
   *
   * @throws BusinessException 재시도 상한까지 전부 중복이면 {@code CASE_NO_GENERATION_FAILED}
   */
  public String generate() {
    String day = LocalDate.now().format(DAY);
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      String candidate = day + "-" + randomCode();
      if (!reportRepository.existsByCaseNo(candidate)) {
        return candidate;
      }
    }
    throw new BusinessException(ErrorCode.CASE_NO_GENERATION_FAILED);
  }

  private String randomCode() {
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
    }
    return code.toString();
  }
}
