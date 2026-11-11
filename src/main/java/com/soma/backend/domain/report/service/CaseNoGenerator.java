package com.soma.backend.domain.report.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 사람용 사건번호(`case_no`) 발급기 — {@code yyyyMMdd-NNNNNN} (예: {@code 20260925-000042}).
 *
 * <h2>왜 DB 시퀀스인가</h2>
 * 전역 시퀀스({@code core.report_case_seq})의 {@code nextval}을 쓴다. {@code nextval}은 무락·원자·비트랜잭셔널이라
 * 여러 요청이 동시에 호출해도 서로 막지 않고 각기 다른 값을 받는다 — 사전확인·재시도·행 락이 모두 불필요하다.
 * 이전의 당일 카운터(행 락)는 같은 날짜 생성을 직렬화해 POST /reports의 처리량 상한이 됐고, 그 회피책이던
 * 랜덤 코드는 다시 충돌 확인·재시도라는 군더더기를 남겼다. 시퀀스는 둘 다 없앤다.
 *
 * <h2>연번(gap)·형식</h2>
 * 롤백된 트랜잭션의 번호는 회수되지 않아 gap이 생긴다(연번 보장 안 함) — gapless와 무경합은 양립할 수 없고,
 * 사람이 부르는 식별자에 연번이 필수는 아니므로 gap을 받아들인다. 유일성은 시퀀스가 보장하므로 날짜는 사람용
 * 접두어일 뿐이며 당일 리셋하지 않는다(리셋은 조율을 재도입해 무경합 목적을 깬다). 코드부는 최소 6자리
 * zero-pad 십진수이고, 시퀀스가 그보다 커지면 자연히 자릿수가 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class CaseNoGenerator {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final int MIN_CODE_WIDTH = 6;

  private final ReportRepository reportRepository;

  /** 오늘 날짜 접두어 + 시퀀스 발급값으로 사건번호를 만든다. */
  public String generate() {
    long seq = reportRepository.nextCaseNoSeq();
    return LocalDate.now().format(DAY) + "-" + code(seq);
  }

  private static String code(long seq) {
    String digits = Long.toString(seq);
    if (digits.length() >= MIN_CODE_WIDTH) {
      return digits;
    }
    return "0".repeat(MIN_CODE_WIDTH - digits.length()) + digits;
  }
}
