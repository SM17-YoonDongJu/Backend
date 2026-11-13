package com.soma.backend.domain.report.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ReportDraftMetricsView} 테스트 픽스처. AI 워커 소유 테이블의 읽기 전용 매핑이라 생성자·세터가 없어
 * 리플렉션으로 조립한다({@link ReportJobFailureViewFixture}와 같은 방식).
 */
public final class ReportDraftMetricsViewFixture {

  /**
   * AI 레포 {@code src/report_worker/README.md} "리포트 수치" 절의 v1 골든 예시({@code tests/test_report_metrics.py}
   * 골든 픽스처 출력, {@code pipeline.nodes}는 2건으로 줄였다). 저장된 {@code draft -> 'metrics'} 그대로의 모양이다.
   */
  public static final String GOLDEN_V1_METRICS = """
      {
        "version": 1,
        "input": {
          "document_count": 3,
          "doc_types": ["policy", "diagnosis", "medical_receipt"],
          "ocr_confidence": {"min": 0.93, "avg": 0.94},
          "doc_type_confidence": {"min": 0.8, "avg": 0.9},
          "pii_masked": {"total": 6, "by_label": {"이름": 3, "전화번호": 2, "주민등록번호": 1},
                         "documents": 4, "free_text": 2}
        },
        "retrieval": {
          "policy_matched": true,
          "evidence": {"terms": 2, "schedule": 1, "case": 2},
          "case_funnel": {"candidates": 12, "deduped": 9, "relevant": 2, "kept": 2},
          "disability_schedule_source": "terms"
        },
        "verification": {
          "disability": {"extracted": 2, "verified": 2, "combined_rate": 20.0, "confidence": "low",
                         "pending_reeval": false,
                         "reclassified": [{"injury": "추간판 탈출", "from": 30.0, "to": 20.0}]},
          "clause_citations": {"cited": 3, "grounded": 2, "ungrounded": ["제99조"], "statute_mentions": 1},
          "judge": {"ran": true, "unavailable": false, "flagged": 1, "checked_chars": 12000,
                    "total_chars": 12480, "coverage": 0.96, "evidence_chunks": 5}
        },
        "pipeline": {
          "nodes": [{"node": "load_context", "ms": 140, "status": "ok"},
                    {"node": "case_search", "ms": 900, "status": "ok"}],
          "node_count": 12, "failed_nodes": [], "total_ms": 73043
        },
        "confidence": {"level": "MEDIUM",
                       "reasons": ["가입금액 미보유 — 제시액 기준 어림 범위", "근거 미확인 주장 1건",
                                   "인용 검증 범위 일부(96%)", "근거에 없는 조항 인용 1건",
                                   "후유장해 지급률 추정 불확실"]}
      }
      """;

  private ReportDraftMetricsViewFixture() {
  }

  /**
   * 초안 수치 행.
   *
   * @param metricsJson {@code (draft -> 'metrics')::text} 값. null은 metrics 키 없음, {@code "null"}은 JSON null이다
   */
  public static ReportDraftMetricsView of(UUID reportId, @Nullable String metricsJson) {
    ReportDraftMetricsView view = BeanUtils.instantiateClass(ReportDraftMetricsView.class);
    ReflectionTestUtils.setField(view, "reportId", reportId);
    ReflectionTestUtils.setField(view, "metricsJson", metricsJson);
    return view;
  }
}
