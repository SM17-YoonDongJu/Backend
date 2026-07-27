package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.adjuster.dto.AdjusterListResponse;
import com.soma.backend.domain.adjuster.repository.AdjusterCardRow;
import com.soma.backend.domain.adjuster.repository.AdjusterListCondition;
import com.soma.backend.domain.adjuster.repository.AdjusterListMetaRow;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.user.entity.Role;

/**
 * 사정사 목록/검색 유스케이스 단위 테스트. 리포지토리를 목으로 두고 서비스 자체 로직만 검증한다
 * — page(1-based)→0-based Pageable 변환, size/page 클램프, 파라미터 trimToNull, 카드 coalesce·verified·평점 규칙.
 */
@ExtendWith(MockitoExtension.class)
class AdjusterListQueryServiceTest {

  @InjectMocks
  private AdjusterListQueryService adjusterListQueryService;

  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;

  @Captor
  private ArgumentCaptor<Pageable> pageableCaptor;

  @Captor
  private ArgumentCaptor<AdjusterListCondition> conditionCaptor;

  /** findAdjusterCards는 넘어온 Pageable을 그대로 되돌려주는 빈 페이지로, meta는 0으로 스텁한다. */
  private void stubEmpty() {
    given(adjusterProfileRepository.findAdjusterCards(any(), any()))
        .willAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));
    given(adjusterProfileRepository.findAdjusterListMeta())
        .willReturn(new AdjusterListMetaRow(0L, 0.0, 0L, 0));
  }

  @Test
  @DisplayName("page(1-based)를 0-based Pageable로 변환하고 pagination.page는 요청값을 그대로 echo한다")
  void convertsOneBasedPageToZeroBasedPageable() {
    stubEmpty();

    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(null, null, null, null, 3, 12);

    then(adjusterProfileRepository).should().findAdjusterCards(any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(12);
    assertThat(result.pagination().page()).isEqualTo(3);
    assertThat(result.pagination().size()).isEqualTo(12);
  }

  @Test
  @DisplayName("page<1이면 1로 보정해 0-based offset 0으로 조회하고 page는 1로 echo한다")
  void clampsPageBelowOneToOne() {
    stubEmpty();

    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(null, null, null, null, 0, 12);

    then(adjusterProfileRepository).should().findAdjusterCards(any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    assertThat(result.pagination().page()).isEqualTo(1);
  }

  @Test
  @DisplayName("size가 최대(50)를 넘으면 50으로 클램프한다")
  void clampsSizeAboveMax() {
    stubEmpty();

    adjusterListQueryService.getAdjusters(null, null, null, null, 1, 100);

    then(adjusterProfileRepository).should().findAdjusterCards(any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("size<1이면 기본값(12)으로 보정한다")
  void clampsSizeBelowOneToDefault() {
    stubEmpty();

    adjusterListQueryService.getAdjusters(null, null, null, null, 1, 0);

    then(adjusterProfileRepository).should().findAdjusterCards(any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(12);
  }

  @Test
  @DisplayName("공백/빈 파라미터는 trimToNull로 필터 미적용(null)이 되고 keyword는 trim된다")
  void trimsBlankParamsToNull() {
    stubEmpty();

    adjusterListQueryService.getAdjusters("  김사정  ", "   ", "", "  ", 1, 12);

    then(adjusterProfileRepository).should().findAdjusterCards(conditionCaptor.capture(), any());
    AdjusterListCondition condition = conditionCaptor.getValue();
    assertThat(condition.keyword()).isEqualTo("김사정");
    assertThat(condition.specialty()).isNull();
    assertThat(condition.region()).isNull();
    assertThat(condition.sort()).isNull();
  }

  @Test
  @DisplayName("카드 매핑 — 값이 있으면 verified(role)·평점·지역 합치기를 그대로 싣는다")
  void mapsCardWithValues() {
    UUID adjusterId = UUID.randomUUID();
    AdjusterCardRow row = new AdjusterCardRow(
        adjusterId, "김사정", "https://cdn/a.png", Role.CERTIFICATED_ADJUSTER,
        List.of("교통사고", "상해"), "장해등급 재산정 전문",
        new BigDecimal("4.50"), 12, 7, 30, List.of("서울", "경기"));
    given(adjusterProfileRepository.findAdjusterCards(any(), any()))
        .willReturn(new PageImpl<>(List.of(row), Pageable.ofSize(12), 1));
    given(adjusterProfileRepository.findAdjusterListMeta())
        .willReturn(new AdjusterListMetaRow(1L, 4.5, 30L, 7));

    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(null, null, null, null, 1, 12);

    AdjusterListResponse.Item item = result.list().get(0);
    assertThat(item.adjusterId()).isEqualTo(adjusterId);
    assertThat(item.name()).isEqualTo("김사정");
    assertThat(item.avatarUrl()).isEqualTo("https://cdn/a.png");
    assertThat(item.verified()).isTrue();
    assertThat(item.specialties()).containsExactly("교통사고", "상해");
    assertThat(item.averageRating()).isEqualTo(4.5);
    assertThat(item.reviewCount()).isEqualTo(12);
    assertThat(item.career()).isEqualTo(7);
    assertThat(item.completedConsultCount()).isEqualTo(30);
    assertThat(item.activityRegion()).isEqualTo("서울·경기");
  }

  @Test
  @DisplayName("카드 매핑 — 비정규화 집계가 null인 신규 사정사는 ''·0·[]·false로 coalesce한다")
  void mapsCardCoalescingNulls() {
    AdjusterCardRow row = new AdjusterCardRow(
        UUID.randomUUID(), "박신입", null, Role.UNCERTIFICATED_ADJUSTER,
        null, null, null, null, null, null, null);
    given(adjusterProfileRepository.findAdjusterCards(any(), any()))
        .willReturn(new PageImpl<>(List.of(row), Pageable.ofSize(12), 1));
    given(adjusterProfileRepository.findAdjusterListMeta())
        .willReturn(new AdjusterListMetaRow(1L, 0.0, 0L, 0));

    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(null, null, null, null, 1, 12);

    AdjusterListResponse.Item item = result.list().get(0);
    assertThat(item.verified()).isFalse();
    assertThat(item.avatarUrl()).isNull();
    assertThat(item.specialties()).isEmpty();
    assertThat(item.headline()).isEmpty();
    assertThat(item.averageRating()).isZero();
    assertThat(item.reviewCount()).isZero();
    assertThat(item.career()).isZero();
    assertThat(item.completedConsultCount()).isZero();
    assertThat(item.activityRegion()).isEmpty();
  }

  @Test
  @DisplayName("카드 매핑 — 후기가 없으면(review_count=0) rating_mean이 있어도 평점은 0.0")
  void averageRatingIsZeroWhenNoReviews() {
    AdjusterCardRow row = new AdjusterCardRow(
        UUID.randomUUID(), "이사정", null, Role.CERTIFICATED_ADJUSTER,
        List.of("화재"), "헤드라인", new BigDecimal("5.00"), 0, 3, 0, List.of("부산"));
    given(adjusterProfileRepository.findAdjusterCards(any(), any()))
        .willReturn(new PageImpl<>(List.of(row), Pageable.ofSize(12), 1));
    given(adjusterProfileRepository.findAdjusterListMeta())
        .willReturn(new AdjusterListMetaRow(1L, 0.0, 0L, 3));

    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(null, null, null, null, 1, 12);

    assertThat(result.list().get(0).averageRating()).isZero();
    assertThat(result.list().get(0).reviewCount()).isZero();
  }
}
