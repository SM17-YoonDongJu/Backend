package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.repository.AdjusterReviewRow;

/**
 * 사정사 평가 목록 응답(GET). {@code list} + {@code pagination}. page는 1-based로 내린다.
 */
public record AdjusterReviewListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> list,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Pagination pagination) {

  public static AdjusterReviewListResponse from(Page<AdjusterReviewRow> page) {
    List<Item> list = page.getContent().stream().map(Item::from).toList();
    Pagination pagination = new Pagination(
        page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
    return new AdjusterReviewListResponse(list, pagination);
  }

  /** 평가 항목. item은 연결 리포트의 사건 유형(없으면 null), reviewedAt은 작성 시각. */
  public record Item(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
      int score,
      @Nullable @Schema(nullable = true) String item,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime reviewedAt,
      @Nullable @Schema(nullable = true) String content) {

    public static Item from(AdjusterReviewRow row) {
      return new Item(
          row.nickname(),
          row.score() == null ? 0 : row.score(),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          row.createdAt(),
          row.review());
    }
  }

  /** 페이지 메타(page는 1-based). */
  public record Pagination(int page, int size, long totalElements, int totalPages, boolean hasNext) {
  }
}
