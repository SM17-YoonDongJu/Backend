package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.annotations.Subselect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ReportDraftMetricsView}의 서브셀렉트가 {@code draft} 전체가 아니라 metrics 서브트리만 읽는지 고정한다
 * (#396 완료 조건 — 본문·질문 같은 큰 자유 텍스트를 가져오지 않는다). 실제 실행 결과는
 * {@code ReportAnalysisMetricsIntegrationTest}가 test_db에서 확인한다.
 */
@DisplayName("ReportDraftMetricsView 서브셀렉트 테스트")
class ReportDraftMetricsViewSubselectTest {

  private static final String METRICS_EXPRESSION = "(draft -> 'metrics')::text";

  private static String subselect() {
    return ReportDraftMetricsView.class.getAnnotation(Subselect.class).value();
  }

  @Test
  @DisplayName("ai 스키마로 한정한 ai.report_drafts에서 metrics 서브트리를 텍스트로 읽는다")
  void readsMetricsSubtreeFromQualifiedTable() {
    assertThat(subselect())
        .contains(METRICS_EXPRESSION)
        .contains("from ai.report_drafts");
  }

  @Test
  @DisplayName("metrics 표현식을 빼면 draft 컬럼을 다른 곳에서 읽지 않는다(draft 전체·sections 미조회)")
  void doesNotReadDraftOutsideMetricsExpression() {
    String withoutMetricsExpression = subselect().replace(METRICS_EXPRESSION, "");

    assertThat(withoutMetricsExpression).doesNotContainPattern("\\bdraft\\b");
  }
}
