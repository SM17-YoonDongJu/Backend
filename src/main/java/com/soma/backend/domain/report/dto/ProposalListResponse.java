package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ProposalRow;

/** GET /reports/{reportId}/proposals 응답(design.md §6). */
public record ProposalListResponse(List<Proposal> list, Pagination pagination) {

  public static ProposalListResponse from(Page<ProposalRow> page) {
    List<Proposal> proposals = page.getContent().stream().map(Proposal::from).toList();
    return new ProposalListResponse(proposals, Pagination.from(page));
  }

  public record Proposal(
      UUID proposalId,
      UUID adjusterId,
      String nickname,
      Double rating,
      String proposalSummary,
      String status,
      LocalDateTime submittedAt) {

    public static Proposal from(ProposalRow row) {
      return new Proposal(
          row.getProposalId(), row.getAdjusterId(), row.getNickname(), row.getRating(),
          row.getProposalSummary(), row.getStatus(), row.getSubmittedAt());
    }
  }
}
