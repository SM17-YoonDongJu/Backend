package com.soma.backend.domain.adjuster.dto;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;

import com.soma.backend.domain.adjuster.repository.AdjusterCardRow;
import com.soma.backend.domain.adjuster.repository.AdjusterListMetaRow;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.global.common.RegionFormat;

/**
 * 손해사정사 공개 목록/검색 응답(GET /adjusters). FE 계약(adjuster-list.schema)에 맞춰 list·pagination·meta 3블록을
 * 내린다. 필드는 Jackson 전역 설정으로 snake_case 직렬화된다(adjuster_id·avatar_url·total_elements·has_next 등).
 *
 * <p>page는 1-based다(FE useSuspenseInfiniteQuery initialPageParam=1, getNextPageParam=page+1). 카드의 비null
 * 계약 필드는 ""·0·[]로 coalesce하고, average_rating은 후기가 없으면(review_count=0·rating_mean null) 0.0을 내린다
 * (공개 상세 조회와 동일 규칙 — 프론트는 review_count로 평점 유무를 판별). verified는 자격 인증 사정사(RBAC)를 진실로 쓴다.
 */
public record AdjusterListResponse(
    List<Item> list,
    Pagination pagination,
    Meta meta) {

  /** 목록 카드 1건. activity_region은 text[]를 단일 문자열로 합친 값(RegionFormat)이다. */
  public record Item(
      UUID adjusterId,
      String name,
      @Nullable String avatarUrl,
      boolean verified,
      List<String> specialties,
      String headline,
      double averageRating,
      int reviewCount,
      int career,
      int completedConsultCount,
      String activityRegion) {

    public static Item from(AdjusterCardRow row, UnaryOperator<String> urlResolver) {
      int reviewCount = row.reviewCount() == null ? 0 : row.reviewCount();
      double averageRating = reviewCount == 0 || row.ratingMean() == null
          ? 0.0
          : row.ratingMean().doubleValue();
      return new Item(
          row.adjusterId(),
          row.nickname(),
          urlResolver.apply(row.avatarUrl()),
          row.role() == Role.CERTIFICATED_ADJUSTER,
          row.specialties() == null ? List.of() : row.specialties(),
          row.headline() == null ? "" : row.headline(),
          averageRating,
          reviewCount,
          row.career() == null ? 0 : row.career(),
          row.completedConsultCount() == null ? 0 : row.completedConsultCount(),
          RegionFormat.toSingle(row.activityRegion()));
    }
  }

  /** 페이지네이션 메타. page·size는 요청값(1-based), 나머지는 조회 결과다. */
  public record Pagination(
      int page,
      int size,
      long totalElements,
      int totalPages,
      boolean hasNext) {
  }

  /** 상단 통계 밴드(인증 사정사 모수 기준 플랫폼 지표). */
  public record Meta(
      long totalAdjusterCount,
      double averageRating,
      long totalConsultCount,
      int averageCareer) {
  }

  public static AdjusterListResponse from(
      Page<AdjusterCardRow> page, int pageNumber, AdjusterListMetaRow meta,
      UnaryOperator<String> urlResolver) {
    List<Item> items = page.getContent().stream().map(row -> Item.from(row, urlResolver)).toList();
    Pagination pagination = new Pagination(
        pageNumber, page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
    Meta metaBlock = new Meta(
        meta.totalAdjusterCount(), meta.averageRating(), meta.totalConsultCount(), meta.averageCareer());
    return new AdjusterListResponse(items, pagination, metaBlock);
  }
}
