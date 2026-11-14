package com.soma.backend.domain.report.entity;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 재업로드 사유 VO(ACL). AI 워커 소유 계약 컬럼 {@code ai.claim_readiness.reupload_reason}(AI 마이그레이션 013, CHECK
 * 3값)을 도메인 개념으로 번역하는 <b>유일한 지점</b>이다 — 외부 문자열이 도메인 안으로 새지 않게 여기서 막는다.
 *
 * <p><b>{@link AnalysisFailureReason}과 섞지 않는다.</b> 그쪽은 실패 저널 {@code failure_class}의 번역이고 대표 사유
 * 우선순위·재업로드 안내를 함께 소유한다. 이 사유는 다른 테이블·다른 상태({@link AnalysisState#NEEDS_REUPLOAD})의
 * 값이라 리포트당 하나뿐이고(대표 사유를 고를 일이 없다) 안내는 사유와 무관하게 항상 재업로드다
 * ({@link ReportAnalysis#reuploadGuidance()}). 한 enum에 섞으면 두 {@code from}이 서로의 문자열을 오매칭하거나 대표 사유
 * 우선순위가 오염될 수 있다.
 *
 * <p><b>상수 이름이 곧 응답 코드다</b>({@code analysis_failure_reason}·{@code failure_reason} — 프론트 계약,
 * {@code ReportResponseSupport.analysisFailureReason}). 이름을 바꾸면 API 계약이 바뀐다.
 *
 * <p>계약 값 {@code unreadable}에는 상수를 두지 않는다 — 기존 일반 안내("더 선명하게 다시 올려주세요")가 바로 그 사유에
 * 맞춘 문구라 응답도 지금과 같게(사유 null) 둔다. 사용자 문구는 처리 상태와 다음 행동만 서술하고 사용자 과실을 단정하지
 * 않는다("잘못 올렸다"·"빠뜨렸다" 금지, 용어집 §12).
 */
public enum ReuploadReason {

  /** {@code off_domain} — 읽을 수 있지만 본문 단서로 보험·의료 문서 유형을 판정한 문서가 하나도 없다(도메인 부적합). */
  DOMAIN_MISMATCH("off_domain",
      "업로드하신 문서에서 보험 청구 서류를 찾지 못했어요. 보험증권·진단서 같은 청구 서류를 올려주세요."),

  /**
   * {@code missing_required} — 보험 문서는 인식했지만 필수 유형(보험증권·진단서) 중 확인하지 못한 것이 있다. "안
   * 올렸다"가 아니라 "올렸는데 인식하지 못했다"는 뜻이다(AI 010 주석). 이 문구는 확인하지 못한 서류를 특정할 수 없을
   * 때의 문구이고, 특정되면 {@link ReuploadCause#userMessage()}가 서류별 문구를 고른다.
   */
  MISSING_REQUIRED_DOCUMENTS("missing_required", "보험증권과 진단서가 모두 필요해요. 두 서류를 함께 다시 올려주세요.");

  private static final Logger log = LoggerFactory.getLogger(ReuploadReason.class);

  /** 계약상 알려진 값이지만 기존 일반 안내로 내는 값. 모르는 값이 아니므로 경고하지 않는다. */
  private static final Set<String> GENERAL_GUIDANCE_VALUES = Set.of("unreadable");

  private final String externalValue;
  private final String userMessage;

  ReuploadReason(String externalValue, String userMessage) {
    this.externalValue = externalValue;
    this.userMessage = userMessage;
  }

  /** 사유를 확인하지 못한 서류를 특정할 수 없을 때의 사용자 노출 문구(조회 응답·푸시·인앱 알림 공통 출처). */
  public String getUserMessage() {
    return userMessage;
  }

  /**
   * ACL 번역 — {@code reupload_reason} 문자열을 도메인 사유로 옮긴다. 값은 CHECK 제약 값과 정확히 일치해야
   * 한다(대소문자 구분).
   *
   * <p>{@code null}(재업로드 아님·013 이전 판정)과 {@code unreadable}(기존 일반 안내)은 경고 없이 {@code null}이다.
   * 그 밖의 모르는 값은 <b>예외가 아니라 경고 로그 + {@code null}</b>이다 — AI가 CHECK에 값을 늘리는 배포가 Backend보다
   * 먼저 나가도 사용자 응답이 500이 되면 안 된다({@link AnalysisFailureReason#from(String)}과 같은 원칙). {@code null}을
   * 받은 호출자는 기존 일반 안내를 낸다.
   *
   * @param externalValue {@code ai.claim_readiness.reupload_reason} 원값
   * @return 사유별 안내가 있는 사유. 일반 안내로 낼 값이면 {@code null}
   */
  public static @Nullable ReuploadReason from(@Nullable String externalValue) {
    if (externalValue == null || GENERAL_GUIDANCE_VALUES.contains(externalValue)) {
      return null;
    }
    for (ReuploadReason reason : values()) {
      if (reason.externalValue.equals(externalValue)) {
        return reason;
      }
    }
    log.warn("알 수 없는 reupload_reason='{}' — 일반 재업로드 안내로 낸다(AI 계약 확장 여부 확인 필요)", externalValue);
    return null;
  }
}
