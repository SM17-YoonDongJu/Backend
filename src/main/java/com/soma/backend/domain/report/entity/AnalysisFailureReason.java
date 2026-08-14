package com.soma.backend.domain.report.entity;

import java.util.Collection;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OCR 실패 사유 VO(ACL). 외부 계약({@code ai.ocr_job_failures.failure_class} 문자열 5값, CHECK 제약)을
 * 도메인 개념으로 번역하는 **유일한 지점**이다 — 외부 문자열이 도메인 안으로 새지 않게 여기서 막는다
 * (design.md §6-1).
 *
 * <p>사용자 문구·재업로드 안내·대표 사유 우선순위는 함께 움직이는 하나의 도메인 개념이라 enum이 소유한다.
 * 값이 늘어나면 세 가지를 동시에 정의하도록 컴파일러가 강제한다.
 *
 * <p><b>대표 사유 우선순위(작을수록 우선)</b> — 한 리포트에 문서 N건의 실패가 섞일 수 있고, 재업로드 안내는
 * "모든 실패가 재업로드로 해결 가능할 때만" 해야 한다. {@code UNREADABLE_FILE}을 가장 낮은 우선순위에 둬서
 * 다른 사유가 하나라도 섞이면 절대 선택되지 않게 만든다 — "마스킹 잔류 + 파일 손상"에 재업로드를 요구하는
 * 오안내가 구조적으로 불가능해진다(design.md §2-2, §8 E5).
 */
public enum AnalysisFailureReason {

  /** 개인정보 마스킹 잔류. 시스템 한계이며 사용자 과실이 아니다 — 재업로드해도 동일 실패. */
  MASKING_RESIDUAL("masking_residual", 1, "문서를 검토 중입니다", ReuploadGuidance.NOT_SUPPORTED),

  /** OCR 작업 메시지 계약 위반(Backend·AI 간 결함). 사용자 액션 불가 — 운영 알림 대상. */
  SCHEMA_INVALID("schema_invalid", 2, "처리에 실패했어요 — 확인 중입니다", ReuploadGuidance.NOT_SUPPORTED),

  /** OCR 엔진 처리 오류. */
  OCR_ERROR("ocr_error", 3, "처리에 실패했어요 — 확인 중입니다", ReuploadGuidance.HOLD),

  /** 알 수 없는 실패 + 미지의 {@code failure_class} 폴백 대상. */
  UNKNOWN("unknown", 4, "처리에 실패했어요 — 확인 중입니다", ReuploadGuidance.HOLD),

  /** 파일을 읽을 수 없음. **유일하게 사용자 액션(재업로드)으로 해결 가능**하다. */
  UNREADABLE_FILE("unreadable_file", 5, "파일을 읽을 수 없어요. 다시 업로드해주세요", ReuploadGuidance.RECOMMENDED);

  private static final Logger log = LoggerFactory.getLogger(AnalysisFailureReason.class);

  private final String failureClass;
  private final int priority;
  private final String userMessage;
  private final ReuploadGuidance reuploadGuidance;

  AnalysisFailureReason(String failureClass, int priority, String userMessage, ReuploadGuidance reuploadGuidance) {
    this.failureClass = failureClass;
    this.priority = priority;
    this.userMessage = userMessage;
    this.reuploadGuidance = reuploadGuidance;
  }

  /** 외부 계약 문자열({@code failure_class}). 매핑 대조용으로만 노출한다. */
  public String getFailureClass() {
    return failureClass;
  }

  /** 사용자 노출 문구. 처리 상태 서술로만 제한한다(보상금액·의학적 판단·법률 자문 표현 금지). */
  public String getUserMessage() {
    return userMessage;
  }

  public ReuploadGuidance getReuploadGuidance() {
    return reuploadGuidance;
  }

  /**
   * ACL 번역 — {@code failure_class} 문자열을 도메인 사유로 옮긴다. **미지의 값은 예외가 아니라
   * {@link #UNKNOWN} 폴백 + 경고 로그**다(design.md §8 E7). AI팀이 CHECK 목록에 새 값을 추가하는 배포가
   * Backend 배포보다 먼저 나갈 수 있어, 계약 확장이 사용자 500으로 번지면 안 된다(AI 워커의
   * {@code _failure_class()}도 같은 이유로 unknown 폴백을 쓴다).
   */
  public static AnalysisFailureReason from(String failureClass) {
    if (failureClass == null) {
      return UNKNOWN;
    }
    for (AnalysisFailureReason reason : values()) {
      if (reason.failureClass.equals(failureClass)) {
        return reason;
      }
    }
    log.warn("알 수 없는 failure_class='{}' — UNKNOWN으로 폴백한다(AI 계약 확장 여부 확인 필요)", failureClass);
    return UNKNOWN;
  }

  /**
   * 리포트 단위 대표 사유 — 우선순위가 가장 높은(값이 작은) 사유를 고른다. 비어 있으면 {@link #UNKNOWN}
   * (실패 행이 있을 때만 호출되므로 정상 경로에서는 발생하지 않는 방어값).
   */
  public static AnalysisFailureReason representative(Collection<AnalysisFailureReason> reasons) {
    if (reasons == null || reasons.isEmpty()) {
      return UNKNOWN;
    }
    return reasons.stream()
        .min(Comparator.comparingInt(reason -> reason.priority))
        .orElse(UNKNOWN);
  }
}
