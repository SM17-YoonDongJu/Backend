package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * ReportReview 리포지토리 쿼리를 실제 PostgreSQL에서 검증한다.
 *
 * <p>{@code countAcceptedByAdjusterId}(상담 완료 수 재집계 JPQL)는 모수가 "채택(ACCEPTED)"으로 한정되는지
 * (상담 도달 SENT·COUNSELING·REJECTED 제외), {@code findProposalRows}(제안 목록 QueryDSL)는 REJECTED 제외·
 * 최신순 정렬과 rating이 adjuster_profiles.rating_mean을 그대로 읽는지 확인한다.
 *
 * <p>{@code countByStatusGrouped}(필터 탭 배지용 상태별 건수)는 월 경계([월초, 익월초))·사정사 스코프·상태별 건수를
 * 검증한다. 예전 JPQL의 {@code (:monthFrom IS NULL OR ...)}가 PostgreSQL에서 준비 단계부터 거부되던 결함(42P18)을
 * 목 테스트가 놓쳤기 때문에, 월 필터가 없는 경우와 있는 경우를 모두 실DB로 실행한다(#411).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportReviewRepositoryExecutionTest {

  /** countByStatusGrouped 월 필터 경계(#411) — 2026-09의 [monthFrom, monthTo). */
  private static final LocalDateTime SEP_START = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
  private static final LocalDateTime OCT_START = LocalDateTime.of(2026, 10, 1, 0, 0, 0);

  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @Autowired
  private UserRepository userRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private ReportReview saveReview(UUID adjusterId) {
    return reportReviewRepository.save(ReportReview.create(UUID.randomUUID(), adjusterId));
  }

  private User saveAdjusterUser(String nickname) {
    return userRepository.save(
        User.create(nickname, LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
  }

  /** 프로필 생성 프로덕션 경로(자격 승인 플로우)가 아직 없어 리플렉션으로 행만 만든다. */
  private void saveProfileWithRating(UUID userId, BigDecimal ratingMean, int reviewCount) {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", userId);
    profile.refreshRating(ratingMean, reviewCount);
    adjusterProfileRepository.save(profile);
  }

  /** created_at은 엔티티에서 updatable=false라 네이티브 UPDATE로 강제한다(테스트 전용 결정적 타임스탬프). */
  private ReportReview saveProposal(
      UUID reportId, UUID adjusterId, String review, ReviewStatus status, LocalDateTime createdAt) {
    ReportReview proposal = reportReviewRepository.save(ReportReview.create(reportId, adjusterId));
    proposal.updateReviewContent(null, null, null, null, null, review);
    switch (status) {
      case SENT -> {
        // 생성 직후 기본 상태라 전이가 없다.
      }
      case COUNSELING -> proposal.startCounseling();
      case ACCEPTED -> proposal.accept();
      case REJECTED -> proposal.reject();
      default -> throw new IllegalArgumentException("unsupported status: " + status);
    }
    entityManager.flush();
    entityManager.createNativeQuery("UPDATE report_reviews SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", proposal.getId())
        .executeUpdate();
    return proposal;
  }

  /** 검수 한 건을 시드한다. 운영의 (report_id, adjuster_id) UK와 어긋나지 않게 리포트는 매번 새로 만든다. */
  private void seedReview(UUID adjusterId, ReviewStatus status, LocalDateTime createdAt) {
    saveProposal(UUID.randomUUID(), adjusterId, "검수 의견", status, createdAt);
  }

  /**
   * countByStatusGrouped 시드(#411) — 대상 사정사 6건과 다른 사정사 2건. 2026-09 필터 기준으로 월초 정각(SENT)과
   * 월말 1초 전(REJECTED)은 포함, 월초 1초 전(ACCEPTED)과 익월초 정각(SENT)은 제외된다. 필터가 없으면 대상 사정사
   * 6건이 모두 잡혀 SENT 3·COUNSELING 1·REJECTED 1·ACCEPTED 1이다. 다른 사정사 행은 어느 경우에도 섞이면 안 된다.
   */
  private void seedStatusCountFixture(UUID adjusterId) {
    UUID otherAdjusterId = UUID.randomUUID();
    seedReview(adjusterId, ReviewStatus.SENT, SEP_START);
    seedReview(adjusterId, ReviewStatus.SENT, LocalDateTime.of(2026, 9, 15, 10, 0, 0));
    seedReview(adjusterId, ReviewStatus.COUNSELING, LocalDateTime.of(2026, 9, 20, 10, 0, 0));
    seedReview(adjusterId, ReviewStatus.REJECTED, OCT_START.minusSeconds(1));
    seedReview(adjusterId, ReviewStatus.ACCEPTED, SEP_START.minusSeconds(1));
    seedReview(adjusterId, ReviewStatus.SENT, OCT_START);
    seedReview(otherAdjusterId, ReviewStatus.SENT, LocalDateTime.of(2026, 9, 10, 10, 0, 0));
    seedReview(otherAdjusterId, ReviewStatus.ACCEPTED, LocalDateTime.of(2026, 9, 11, 10, 0, 0));
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  @DisplayName("countAcceptedByAdjusterId — 채택 이력이 없으면 0을 돌려준다")
  void countAccepted_noRows_returnsZero() {
    assertThat(reportReviewRepository.countAcceptedByAdjusterId(UUID.randomUUID())).isZero();
  }

  @Test
  @DisplayName("countAcceptedByAdjusterId — ACCEPTED만 센다(SENT·COUNSELING·REJECTED 제외)")
  void countAccepted_countsOnlyAcceptedStatus() {
    UUID adjusterId = UUID.randomUUID();
    saveReview(adjusterId).accept();
    saveReview(adjusterId).accept();
    saveReview(adjusterId).startCounseling();
    saveReview(adjusterId).reject();
    saveReview(adjusterId);

    assertThat(reportReviewRepository.countAcceptedByAdjusterId(adjusterId)).isEqualTo(2L);
  }

  @Test
  @DisplayName("countAcceptedByAdjusterId — 다른 사정사의 채택 건은 섞이지 않는다")
  void countAccepted_isScopedByAdjuster() {
    UUID adjusterId = UUID.randomUUID();
    saveReview(adjusterId).accept();
    saveReview(UUID.randomUUID()).accept();

    assertThat(reportReviewRepository.countAcceptedByAdjusterId(adjusterId)).isEqualTo(1L);
  }

  @Test
  @DisplayName("findProposalRows — 최신순으로 정렬하고 REJECTED 제안은 목록·총건수 모두에서 제외한다")
  void findProposalRows_ordersByNewestAndExcludesRejected() {
    UUID reportId = UUID.randomUUID();
    LocalDateTime base = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    User older = saveAdjusterUser("먼저 보낸 사정사");
    User newer = saveAdjusterUser("나중에 보낸 사정사");
    User rejected = saveAdjusterUser("거절당한 사정사");
    saveProposal(reportId, older.getId(), "먼저 제안", ReviewStatus.SENT, base);
    saveProposal(reportId, newer.getId(), "나중 제안", ReviewStatus.COUNSELING, base.plusHours(1));
    saveProposal(reportId, rejected.getId(), "거절된 제안", ReviewStatus.REJECTED, base.plusHours(2));
    saveProposal(UUID.randomUUID(), older.getId(), "다른 리포트 제안", ReviewStatus.SENT, base.plusHours(3));
    entityManager.flush();
    entityManager.clear();

    Page<ProposalRow> page = reportReviewRepository.findProposalRows(reportId, PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(2L);
    List<ProposalRow> rows = page.getContent();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).adjusterId()).isEqualTo(newer.getId());
    assertThat(rows.get(0).nickname()).isEqualTo("나중에 보낸 사정사");
    assertThat(rows.get(0).proposalSummary()).isEqualTo("나중 제안");
    assertThat(rows.get(0).status()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(rows.get(0).submittedAt()).isEqualTo(base.plusHours(1));
    assertThat(rows.get(1).adjusterId()).isEqualTo(older.getId());
    assertThat(rows.get(1).status()).isEqualTo(ReviewStatus.SENT);
  }

  @Test
  @DisplayName("findProposalRows — rating은 adjuster_profiles.rating_mean(scale 2)을 그대로 읽는다")
  void findProposalRows_readsRatingMeanFromProfile() {
    UUID reportId = UUID.randomUUID();
    User adjuster = saveAdjusterUser("평점 있는 사정사");
    saveProfileWithRating(adjuster.getId(), new BigDecimal("4.333"), 3);
    saveProposal(reportId, adjuster.getId(), "제안", ReviewStatus.SENT, LocalDateTime.of(2026, 9, 1, 10, 0, 0));
    entityManager.flush();
    entityManager.clear();

    Page<ProposalRow> page = reportReviewRepository.findProposalRows(reportId, PageRequest.of(0, 10));

    assertThat(page.getContent()).singleElement()
        .satisfies(row -> assertThat(row.rating()).isEqualByComparingTo("4.33"));
  }

  @Test
  @DisplayName("findProposalRows — 프로필 행이 없거나 평가가 0건이면 rating은 null이다")
  void findProposalRows_returnsNullRatingWithoutProfileOrReviews() {
    UUID reportId = UUID.randomUUID();
    LocalDateTime base = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    User noProfile = saveAdjusterUser("프로필 없는 사정사");
    User noReviews = saveAdjusterUser("평가 없는 사정사");
    saveProfileWithRating(noReviews.getId(), null, 0);
    saveProposal(reportId, noProfile.getId(), "제안1", ReviewStatus.SENT, base);
    saveProposal(reportId, noReviews.getId(), "제안2", ReviewStatus.SENT, base.plusHours(1));
    entityManager.flush();
    entityManager.clear();

    Page<ProposalRow> page = reportReviewRepository.findProposalRows(reportId, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(2).allSatisfy(row -> assertThat(row.rating()).isNull());
  }

  @Test
  @DisplayName("findProposalRows — 페이지 크기를 넘는 제안은 잘라내되 총건수는 전체를 센다")
  void findProposalRows_paginates() {
    UUID reportId = UUID.randomUUID();
    LocalDateTime base = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    User first = saveAdjusterUser("사정사1");
    User second = saveAdjusterUser("사정사2");
    User third = saveAdjusterUser("사정사3");
    saveProposal(reportId, first.getId(), "제안1", ReviewStatus.SENT, base);
    saveProposal(reportId, second.getId(), "제안2", ReviewStatus.SENT, base.plusHours(1));
    saveProposal(reportId, third.getId(), "제안3", ReviewStatus.SENT, base.plusHours(2));
    entityManager.flush();
    entityManager.clear();

    Page<ProposalRow> page = reportReviewRepository.findProposalRows(reportId, PageRequest.of(1, 2));

    assertThat(page.getTotalElements()).isEqualTo(3L);
    assertThat(page.getContent()).singleElement()
        .satisfies(row -> assertThat(row.adjusterId()).isEqualTo(first.getId()));
  }

  @Test
  @DisplayName("countByStatusGrouped — 검수가 없는 사정사면 월 필터가 있든 없든 빈 목록이다")
  void countByStatusGrouped_noRows_returnsEmpty() {
    UUID adjusterId = UUID.randomUUID();

    assertThat(reportReviewRepository.countByStatusGrouped(adjusterId, null, null)).isEmpty();
    assertThat(reportReviewRepository.countByStatusGrouped(adjusterId, SEP_START, OCT_START)).isEmpty();
  }

  @Test
  @DisplayName("countByStatusGrouped — 월 필터가 없으면 그 사정사의 전 기간 검수를 상태별로 센다")
  void countByStatusGrouped_withoutMonth_countsAllPeriods() {
    UUID adjusterId = UUID.randomUUID();
    seedStatusCountFixture(adjusterId);

    List<StatusCount> counts = reportReviewRepository.countByStatusGrouped(adjusterId, null, null);

    // 월 경계 밖 행(월초 1초 전 ACCEPTED·익월초 정각 SENT)도 세고, 다른 사정사 행(SENT·ACCEPTED)은 섞이지 않는다.
    assertThat(counts).containsExactlyInAnyOrder(
        new StatusCount(ReviewStatus.SENT, 3L),
        new StatusCount(ReviewStatus.COUNSELING, 1L),
        new StatusCount(ReviewStatus.REJECTED, 1L),
        new StatusCount(ReviewStatus.ACCEPTED, 1L));
  }

  @Test
  @DisplayName("countByStatusGrouped — 월 필터는 [월초, 익월초) 반개구간이고 건수 0인 상태는 결과에 없다")
  void countByStatusGrouped_withMonth_countsHalfOpenRange() {
    UUID adjusterId = UUID.randomUUID();
    seedStatusCountFixture(adjusterId);

    List<StatusCount> counts = reportReviewRepository.countByStatusGrouped(adjusterId, SEP_START, OCT_START);

    // SENT 2 = 월초 정각(하한 포함) + 9/15. 익월초 정각 행은 상한 배타라 빠진다. 월초 1초 전 ACCEPTED만 있던
    // ACCEPTED는 0건이라 0으로 오지 않고 행 자체가 없다(0 채움은 서비스 몫).
    assertThat(counts).containsExactlyInAnyOrder(
        new StatusCount(ReviewStatus.SENT, 2L),
        new StatusCount(ReviewStatus.COUNSELING, 1L),
        new StatusCount(ReviewStatus.REJECTED, 1L));
  }

  @Test
  @DisplayName("findReviewedReportRows — 목록 총건수도 상태별 건수와 같은 [월초, 익월초) 범위로 센다")
  void findReviewedReportRows_withMonth_totalMatchesStatusCountRange() {
    UUID adjusterId = UUID.randomUUID();
    seedStatusCountFixture(adjusterId);

    Page<ReviewedReportRow> page =
        reportReviewRepository.findReviewedReportRows(adjusterId, null, SEP_START, OCT_START, PageRequest.of(0, 20));

    // 9월 범위의 4건(월초 정각 SENT·9/15 SENT·9/20 COUNSELING·월말 1초 전 REJECTED)만 센다. 이 시드에는 리포트·사용자
    // 행이 없어 본문(inner join)은 비지만, 총건수는 rv만으로 세므로 월 필터가 빠지면 6건이 된다.
    assertThat(page.getTotalElements()).isEqualTo(4L);
  }
}
