package com.soma.backend.domain.report.repository;

import java.util.UUID;

/** API#1·#2 검수 대기 목록 네이티브 쿼리 프로젝션. */
public interface PendingReviewRow {

  UUID getReportId();

  String getCaseNo();

  String getTitle();

  String getAccidentType();

  String getRegion();

  String getStatus();

  Long getClaimedMinAmount();

  Long getClaimedMaxAmount();

  Long getOfferedAmount();

  Long getIssueCount();

  Boolean getHeld();
}
