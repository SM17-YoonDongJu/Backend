package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
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

  /** 접수 시각(REPORTS.created_at). 프론트가 마감 라벨(오늘 마감/N일 남음)을 이 값으로 계산한다. */
  LocalDateTime getCreatedAt();
}
