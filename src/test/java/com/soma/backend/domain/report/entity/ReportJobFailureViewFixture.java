package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ReportJobFailureView} 테스트 픽스처. AI 워커 소유 테이블의 읽기 전용 매핑이라 생성자·세터가 없어
 * 리플렉션으로 조립한다. 뷰가 terminal 행만 노출하므로 terminal 여부를 고르는 팩터리는 두지 않는다.
 */
public final class ReportJobFailureViewFixture {

  private ReportJobFailureViewFixture() {
  }

  /** 리포트 생성 확정 실패 행(재전달 한도 소진으로 poison 확정). */
  public static ReportJobFailureView terminal(UUID reportId, String failureClass, LocalDateTime firstFailedAt) {
    ReportJobFailureView view = BeanUtils.instantiateClass(ReportJobFailureView.class);
    ReflectionTestUtils.setField(view, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(view, "reportId", reportId);
    ReflectionTestUtils.setField(view, "failureClass", failureClass);
    ReflectionTestUtils.setField(view, "firstFailedAt", firstFailedAt);
    ReflectionTestUtils.setField(view, "lastFailedAt", firstFailedAt.plusMinutes(30));
    return view;
  }
}
