package com.soma.backend.domain.report.entity;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ClaimReadinessView} 테스트 픽스처. 이 뷰는 AI 워커 소유 테이블의 읽기 전용 매핑이라 생성자·세터가
 * 없다(설계 의도) — 테스트에서는 리플렉션으로 조립한다.
 */
public final class ClaimReadinessViewFixture {

  private ClaimReadinessViewFixture() {
  }

  /**
   * 청구 판정 행 하나. {@code claim_id}는 매번 새로 만든다(PK).
   *
   * @param reportId        {@code report_id}
   * @param reuploadReason  {@code reupload_reason} 원값(null 가능)
   * @param missingDocTypes {@code missing_doc_types} 원값(null 가능)
   */
  public static ClaimReadinessView of(UUID reportId, String reuploadReason, List<String> missingDocTypes) {
    ClaimReadinessView view = BeanUtils.instantiateClass(ClaimReadinessView.class);
    ReflectionTestUtils.setField(view, "claimId", UUID.randomUUID().toString());
    ReflectionTestUtils.setField(view, "reportId", reportId);
    ReflectionTestUtils.setField(view, "missingDocTypes", missingDocTypes);
    ReflectionTestUtils.setField(view, "reuploadReason", reuploadReason);
    return view;
  }
}
