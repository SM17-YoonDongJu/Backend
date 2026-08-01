package com.soma.backend.domain.report.dto;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 사정사 평가 등록 요청(POST /adjusters/{adjusterId}/reviews). score 1~5 필수, content(후기) 선택.
 * score 범위 위반은 400으로 거른다.
 */
public record CreateAdjusterReviewRequest(
    @NotNull(message = "평점은 필수입니다.")
        @Min(value = 1, message = "평점은 1~5입니다.")
        @Max(value = 5, message = "평점은 1~5입니다.")
        Integer score,
    @Nullable @Size(max = 1000) String content) {
}
