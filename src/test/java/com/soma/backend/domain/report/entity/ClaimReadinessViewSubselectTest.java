package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.annotations.Subselect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaimReadinessView}의 서브셀렉트가 AI 013이 연 4개 컬럼만 고르는지 고정한다(#401).
 *
 * <p>013은 backend 롤에 <b>컬럼 한정</b> {@code GRANT SELECT (claim_id, report_id, missing_doc_types, reupload_reason)}만
 * 연다. 서브셀렉트가 다른 컬럼이나 {@code *}를 고르면 바깥 쿼리가 그 컬럼을 쓰지 않아도 운영에서만 42501이 나는데, 테스트
 * 롤은 테이블 소유자라 통합 테스트로는 잡히지 않는다 — 그래서 문자열로 고정한다. 컬럼을 늘려야 하면 AI 쪽 GRANT부터
 * 넓히고 이 테스트를 함께 고친다. 실제 실행 결과는 {@code ReuploadReasonIntegrationTest}가 test_db에서 확인한다.
 */
@DisplayName("ClaimReadinessView 서브셀렉트 테스트")
class ClaimReadinessViewSubselectTest {

  private static String normalizedSubselect() {
    return ClaimReadinessView.class.getAnnotation(Subselect.class).value().strip().replaceAll("\\s+", " ");
  }

  @Test
  @DisplayName("ai 스키마로 한정한 ai.claim_readiness에서 GRANT된 4개 컬럼만 읽는다(*·where 없음)")
  void selectsOnlyGrantedColumnsFromQualifiedTable() {
    assertThat(normalizedSubselect())
        .isEqualTo("select claim_id, report_id, missing_doc_types, reupload_reason from ai.claim_readiness");
  }
}
