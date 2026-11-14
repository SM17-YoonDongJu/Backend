package com.soma.backend.domain.report.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.soma.backend.domain.report.entity.ReportDraftMetricsView;

/**
 * {@code ai.report_drafts} metrics 읽기 전용 리포지토리({@link ReportDraftMetricsView} 참조 — AI 워커 소유 테이블).
 *
 * <p>{@code JpaRepository}가 아니라 {@code Repository}를 확장해 {@code save}·{@code delete}가 타입 레벨에서 아예
 * 없게 한다({@link OcrResultViewRepository}와 같은 이유 — 엔티티의 {@code @Immutable}과 이중 방어).
 */
public interface ReportDraftMetricsViewRepository extends Repository<ReportDraftMetricsView, UUID> {

  /** 리포트의 초안 수치를 조회한다. {@code report_id}가 PK라 0~1행이다. */
  Optional<ReportDraftMetricsView> findByReportId(UUID reportId);
}
