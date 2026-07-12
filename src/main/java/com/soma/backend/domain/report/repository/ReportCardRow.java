package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** GET /reports 유저 대시보드 카드 목록 네이티브 쿼리 프로젝션(design.md §6 ReportCardListResponse.Card). */
public interface ReportCardRow {

  UUID getReportId();

  String getStatus();

  String getAccidentType();

  String getTitle();

  LocalDateTime getCreatedAt();

  String getCaseNo();

  Long getClaimedMinAmount();

  Long getClaimedMaxAmount();

  Long getProposalCount();

  LocalDateTime getReviewedAt();

  String getAdjusterNickname();

  Long getConfirmedMinAmount();

  Long getConfirmedMaxAmount();

  Double getRating();
}
