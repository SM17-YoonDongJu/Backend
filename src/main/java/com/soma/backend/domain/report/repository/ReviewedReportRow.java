package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** API#5 내 검수 내역 목록 네이티브 쿼리 프로젝션. */
public interface ReviewedReportRow {

  UUID getReportId();

  String getCaseNo();

  String getTitle();

  String getAccidentType();

  String getRegion();

  String getOutcome();

  LocalDateTime getReviewedAt();
}
