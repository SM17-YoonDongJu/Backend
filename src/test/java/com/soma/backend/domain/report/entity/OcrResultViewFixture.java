package com.soma.backend.domain.report.entity;

import java.util.UUID;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OcrResultView} 테스트 픽스처. 이 뷰는 AI 워커 소유 테이블의 읽기 전용 매핑이라 생성자·세터가
 * 없다(설계 의도) — 테스트에서는 리플렉션으로 조립한다.
 */
public final class OcrResultViewFixture {

  private OcrResultViewFixture() {
  }

  /** 품질 미달(needs_reupload) 판정 행. */
  public static OcrResultView needsReupload(UUID reportId, UUID attachmentId, String docType) {
    return build(reportId, attachmentId, docType, "needs_reupload");
  }

  private static OcrResultView build(UUID reportId, UUID attachmentId, String docType, String ocrQuality) {
    OcrResultView view = BeanUtils.instantiateClass(OcrResultView.class);
    ReflectionTestUtils.setField(view, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(view, "reportId", reportId);
    ReflectionTestUtils.setField(view, "attachmentId", attachmentId);
    ReflectionTestUtils.setField(view, "docType", docType);
    ReflectionTestUtils.setField(view, "ocrQuality", ocrQuality);
    return view;
  }
}
