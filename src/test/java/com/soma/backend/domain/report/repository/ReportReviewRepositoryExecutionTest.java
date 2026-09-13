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
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportReviewRepositoryExecutionTest {

  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @Autowired
  private UserRepository userRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private ReportReview saveReview(UUID adjusterId) {
    return reportReviewRepository.save(new ReportReview(UUID.randomUUID(), adjusterId));
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
    ReportReview proposal = reportReviewRepository.save(new ReportReview(reportId, adjusterId));
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
}
