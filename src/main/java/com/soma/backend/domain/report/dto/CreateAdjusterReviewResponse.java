package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.AdjusterReview;

/** 평가 등록 응답(POST). */
public record CreateAdjusterReviewResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adjusterId,
    int score,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {

  public static CreateAdjusterReviewResponse from(AdjusterReview review) {
    return new CreateAdjusterReviewResponse(
        review.getId(), review.getAdjusterId(), review.getScore(), review.getCreatedAt());
  }
}
