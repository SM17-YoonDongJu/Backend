package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OcrJobFailureView} 테스트 픽스처. 이 뷰는 AI 워커 소유 테이블의 읽기 전용 매핑이라 생성자·세터가
 * 없다(설계 의도) — 테스트에서는 리플렉션으로 조립한다.
 */
public final class OcrJobFailureViewFixture {

  private OcrJobFailureViewFixture() {
  }

  /** 확정 실패(terminal=true) 행. 사용자에게 노출되는 유일한 종류다. */
  public static OcrJobFailureView terminal(
      UUID reportId, UUID attachmentId, String failureClass, LocalDateTime firstFailedAt) {
    return build(reportId, attachmentId, failureClass, true, firstFailedAt);
  }

  /** 일시 실패(terminal=false) 행. 재전달로 회복될 수 있어 조회에서 걸러져야 한다(§8 E1). */
  public static OcrJobFailureView nonTerminal(
      UUID reportId, UUID attachmentId, String failureClass, LocalDateTime firstFailedAt) {
    return build(reportId, attachmentId, failureClass, false, firstFailedAt);
  }

  private static OcrJobFailureView build(
      UUID reportId, UUID attachmentId, String failureClass, boolean terminal, LocalDateTime firstFailedAt) {
    OcrJobFailureView view = BeanUtils.instantiateClass(OcrJobFailureView.class);
    ReflectionTestUtils.setField(view, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(view, "reportId", reportId);
    ReflectionTestUtils.setField(view, "attachmentId", attachmentId);
    ReflectionTestUtils.setField(view, "failureClass", failureClass);
    ReflectionTestUtils.setField(view, "errorType", "RuntimeError");
    ReflectionTestUtils.setField(view, "terminal", terminal);
    ReflectionTestUtils.setField(view, "firstFailedAt", firstFailedAt);
    ReflectionTestUtils.setField(view, "lastFailedAt", firstFailedAt);
    return view;
  }
}
