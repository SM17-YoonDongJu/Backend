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

  String getStatus();

  LocalDateTime getReviewedAt();

  /** 사정사가 확정한 예상 보상 금액 범위 하한(report_reviews.estimate_min_amount). 미확정 시 null. */
  Long getConfirmedMinAmount();

  /** 사정사가 확정한 예상 보상 금액 범위 상한(report_reviews.estimate_max_amount). 미확정 시 null. */
  Long getConfirmedMaxAmount();
}
