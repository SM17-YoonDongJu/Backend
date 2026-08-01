package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** GET /reports/{reportId}/proposals 목록 네이티브 쿼리 프로젝션(design.md §6 ProposalListResponse.Proposal). */
public interface ProposalRow {

  UUID getProposalId();

  UUID getAdjusterId();

  String getNickname();

  Double getRating();

  String getProposalSummary();

  String getStatus();

  LocalDateTime getSubmittedAt();
}
