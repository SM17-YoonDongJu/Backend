package com.soma.backend.domain.adjuster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사정사 공개 목록/검색 QueryDSL이 실제 PostgreSQL에서 SQL로 번역·실행되는지 검증한다
 * (adjuster_profiles×users user_id 조인 projection, keyword containsIgnoreCase OR, specialty·region의
 * text[] array_contains, sort별 desc nullsLast, meta의 count·avg(rating_mean)·sum(consult)·avg(career)).
 * 데이터가 없어도 SQL은 준비·실행되므로 번역 불가·문법 오류가 있으면 여기서 드러난다
 * (UserDashboardRepositoryExecutionTest·AdjusterHomeRepositoryExecutionTest 관례).
 */
@SpringBootTest
@ActiveProfiles("test")
class AdjusterProfileRepositoryExecutionTest {

  private static final Pageable PAGEABLE = PageRequest.of(0, 12);

  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;

  private static AdjusterListCondition condition(
      String keyword, String specialty, String region, String sort) {
    return new AdjusterListCondition(keyword, specialty, region, sort);
  }

  private void assertExecutesEmpty(AdjusterListCondition condition) {
    Page<AdjusterCardRow> page = adjusterProfileRepository.findAdjusterCards(condition, PAGEABLE);

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isZero();
  }

  @Test
  @DisplayName("findAdjusterCards — 필터 없음: adjuster_profiles×users 조인 projection이 실행된다(빈 DB에서 empty)")
  void findAdjusterCards_noFilter_executes() {
    assertExecutesEmpty(condition(null, null, null, null));
  }

  @Test
  @DisplayName("findAdjusterCards — keyword: 닉네임·헤드라인 containsIgnoreCase OR 필터가 실행된다")
  void findAdjusterCards_keyword_executes() {
    assertExecutesEmpty(condition("김", null, null, null));
  }

  @Test
  @DisplayName("findAdjusterCards — specialty: specialties text[] array_contains 필터가 실행된다")
  void findAdjusterCards_specialty_executes() {
    assertExecutesEmpty(condition(null, "교통사고", null, null));
  }

  @Test
  @DisplayName("findAdjusterCards — specialty '전체'는 미필터로 실행된다")
  void findAdjusterCards_specialtyAll_executes() {
    assertExecutesEmpty(condition(null, "전체", null, null));
  }

  @Test
  @DisplayName("findAdjusterCards — region: activity_region text[] array_contains 필터가 실행된다")
  void findAdjusterCards_region_executes() {
    assertExecutesEmpty(condition(null, null, "서울", null));
  }

  @Test
  @DisplayName("findAdjusterCards — region 콤마 다중 토큰(서울,경기) OR 필터가 실행된다")
  void findAdjusterCards_regionMultiToken_executes() {
    assertExecutesEmpty(condition(null, null, "서울,경기", null));
  }

  @Test
  @DisplayName("findAdjusterCards — sort=rating: rating_mean desc nullsLast 정렬이 실행된다")
  void findAdjusterCards_sortRating_executes() {
    assertExecutesEmpty(condition(null, null, null, "rating"));
  }

  @Test
  @DisplayName("findAdjusterCards — sort=review: review_count desc nullsLast 정렬이 실행된다")
  void findAdjusterCards_sortReview_executes() {
    assertExecutesEmpty(condition(null, null, null, "review"));
  }

  @Test
  @DisplayName("findAdjusterCards — sort=career: career desc nullsLast 정렬이 실행된다")
  void findAdjusterCards_sortCareer_executes() {
    assertExecutesEmpty(condition(null, null, null, "career"));
  }

  @Test
  @DisplayName("findAdjusterCards — sort=consultCount: completed_consult_count desc nullsLast 정렬이 실행된다")
  void findAdjusterCards_sortConsultCount_executes() {
    assertExecutesEmpty(condition(null, null, null, "consultCount"));
  }

  @Test
  @DisplayName("findAdjusterCards — 모든 필터 결합(keyword+specialty+region+sort)이 실행된다")
  void findAdjusterCards_allFilters_executes() {
    assertExecutesEmpty(condition("김", "교통사고", "서울,경기", "career"));
  }

  /**
   * 집계 갱신 경로가 쓰는 잠금 조회 — {@code PESSIMISTIC_WRITE} + JPQL 조합이 PostgreSQL Dialect에서 깨지지
   * 않고 실행되는지까지만 본다(다른 실행 테스트와 같은 성격). 대상 행이 없으면 잠기는 행도 없으므로
   * <b>잠금의 효과(동시 갱신 직렬화)는 여기서 검증되지 않는다</b> — 그건 실제로 두 트랜잭션을 붙이는
   * {@code AdjusterReviewCommandServiceConcurrencyIntegrationTest}(E11)·
   * {@code ChatConsultationAcceptConcurrencyIntegrationTest}(E12)가 맡는다.
   * 잠금은 트랜잭션을 요구하므로 이 테스트만 트랜잭션 안에서 돈다(다른 테스트는 잠금과 무관해 기존대로 둔다).
   */
  @Test
  @Transactional
  @DisplayName("findByUserIdForUpdate — PESSIMISTIC_WRITE 잠금 조회가 실행된다(대상 행이 없으면 empty)")
  void findByUserIdForUpdate_executes() {
    assertThat(adjusterProfileRepository.findByUserIdForUpdate(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("findAdjusterListMeta — count·avg(rating_mean)·sum(consult)·avg(career) 집계가 실행된다(빈 DB에서 0)")
  void findAdjusterListMeta_executes() {
    AdjusterListMetaRow meta = adjusterProfileRepository.findAdjusterListMeta();

    assertThat(meta).isNotNull();
    assertThat(meta.totalAdjusterCount()).isZero();
    assertThat(meta.averageRating()).isZero();
    assertThat(meta.totalConsultCount()).isZero();
    assertThat(meta.averageCareer()).isZero();
  }
}
