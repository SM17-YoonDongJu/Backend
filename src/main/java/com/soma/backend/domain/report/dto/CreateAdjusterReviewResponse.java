package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AdjusterReview;

/** 평가 등록 응답(POST). */
public record CreateAdjusterReviewResponse(UUID reviewId, UUID adjusterId, int score, LocalDateTime createdAt) {

  public static CreateAdjusterReviewResponse from(AdjusterReview review) {
    return new CreateAdjusterReviewResponse(
        review.getId(), review.getAdjusterId(), review.getScore(), review.getCreatedAt());
  }
}
