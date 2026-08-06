package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.repository.ProposalRow;

/** GET /reports/{reportId}/proposals 응답(design.md §6). */
public record ProposalListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Proposal> list,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Pagination pagination) {

  public static ProposalListResponse from(Page<ProposalRow> page) {
    List<Proposal> proposals = page.getContent().stream().map(Proposal::from).toList();
    return new ProposalListResponse(proposals, Pagination.from(page));
  }

  /** rating은 사정사의 사건 평점 평균(adjuster_reviews 없으면 null), proposalSummary는 review 원문(미작성 시 null)이다. */
  public record Proposal(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adjusterId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
      @Schema(nullable = true) Double rating,
      @Schema(nullable = true) String proposalSummary,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime submittedAt) {

    public static Proposal from(ProposalRow row) {
      return new Proposal(
          row.getProposalId(), row.getAdjusterId(), row.getNickname(), row.getRating(),
          row.getProposalSummary(), row.getStatus(), row.getSubmittedAt());
    }
  }
}
