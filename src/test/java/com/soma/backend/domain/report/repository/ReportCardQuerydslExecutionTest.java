package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * findUserReportCards(QueryDSL) 실행 검증 — 실제 test_db에 시드 데이터를 넣고 소유자 스코프·status 필터·
 * 페이지네이션·정렬·ACCEPTED 제안 조인(있음/없음)·proposalCount(REJECTED 제외)를 확인한다.
 * @Transactional로 각 테스트 종료 시 롤백돼 다른 실행 테스트(빈 DB 가정)를 오염시키지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportCardQuerydslExecutionTest {

  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("소유자 스코프 — userId 소유 리포트만 반환하고 타인 리포트는 제외한다")
  void findsOnlyOwnerReports() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID a1 = saveReport(userA, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "OS-A1", null, null);
    UUID a2 = saveReport(userA, ReportStatus.COUNSELING, AccidentType.OTHER, "OS-A2", null, null);
    UUID b1 = saveReport(userB, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "OS-B1", null, null);
    flushAndClear();

    Page<ReportCardRow> pageA = reportRepository.findUserReportCards(userA, null, PageRequest.of(0, 10));

    assertThat(pageA.getTotalElements()).isEqualTo(2);
    assertThat(pageA.getContent()).extracting(ReportCardRow::reportId)
        .containsExactlyInAnyOrder(a1, a2)
        .doesNotContain(b1);
  }

  @Test
  @DisplayName("status 필터 — 지정 상태만 반환하고, 필터 없으면 전 상태를 반환한다")
  void filtersByStatus() {
    UUID userId = UUID.randomUUID();
    saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "SF-1", null, null);
    UUID counseling = saveReport(userId, ReportStatus.COUNSELING, AccidentType.OTHER, "SF-2", null, null);
    saveReport(userId, ReportStatus.CLOSED, AccidentType.OTHER, "SF-3", null, null);
    flushAndClear();

    Page<ReportCardRow> all = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 10));
    assertThat(all.getTotalElements()).isEqualTo(3);

    Page<ReportCardRow> onlyCounseling =
        reportRepository.findUserReportCards(userId, ReportStatus.COUNSELING, PageRequest.of(0, 10));
    assertThat(onlyCounseling.getContent()).extracting(ReportCardRow::reportId).containsExactly(counseling);
    assertThat(onlyCounseling.getContent().get(0).status()).isEqualTo(ReportStatus.COUNSELING);
  }

  @Test
  @DisplayName("페이지네이션 — created_at DESC(최신순) 정렬 + total/hasNext가 정확하다")
  void paginatesNewestFirst() {
    UUID userId = UUID.randomUUID();
    List<UUID> ids = new ArrayList<>();
    LocalDateTime base = LocalDateTime.of(2026, 7, 1, 9, 0);
    for (int idx = 0; idx < 5; idx++) {
      ids.add(saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "PG-" + idx, null, null));
    }
    entityManager.flush();
    for (int idx = 0; idx < 5; idx++) {
      overrideCreatedAt(ids.get(idx), base.plusMinutes(idx));
    }
    flushAndClear();

    Page<ReportCardRow> page0 = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 2));
    assertThat(page0.getTotalElements()).isEqualTo(5);
    assertThat(page0.getTotalPages()).isEqualTo(3);
    assertThat(page0.hasNext()).isTrue();
    // 최신순: idx4(가장 늦은 created_at) → idx3
    assertThat(page0.getContent()).extracting(ReportCardRow::reportId)
        .containsExactly(ids.get(4), ids.get(3));

    Page<ReportCardRow> page2 = reportRepository.findUserReportCards(userId, null, PageRequest.of(2, 2));
    assertThat(page2.getContent()).extracting(ReportCardRow::reportId).containsExactly(ids.get(0));
    assertThat(page2.hasNext()).isFalse();
  }

  @Test
  @DisplayName("ACCEPTED 제안 있음 — 확정 사정사·견적·평점을 붙이고 proposalCount는 REJECTED를 제외한다")
  void joinsAcceptedProposal() {
    UUID userId = UUID.randomUUID();
    UUID reportId = saveReport(userId, ReportStatus.CLOSED, AccidentType.TRAFFIC, "AC-1", 1_000_000L, 2_000_000L);
    UUID adjusterA = saveAdjuster("김사정", new BigDecimal("4.30"));
    saveReview(reportId, adjusterA, ReviewStatus.ACCEPTED, 5_000_000L, 8_000_000L);
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.SENT, 3_000_000L, 4_000_000L);
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.REJECTED, 1_000_000L, 2_000_000L);
    flushAndClear();

    Page<ReportCardRow> page = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    ReportCardRow row = page.getContent().get(0);
    assertThat(row.reportId()).isEqualTo(reportId);
    assertThat(row.status()).isEqualTo(ReportStatus.CLOSED);
    assertThat(row.accidentType()).isEqualTo(AccidentType.TRAFFIC);
    assertThat(row.caseNo()).isEqualTo("AC-1");
    assertThat(row.proposalCount()).isEqualTo(2L); // ACCEPTED + SENT (REJECTED 제외)
    assertThat(row.reviewedAt()).isNotNull();
    assertThat(row.adjusterNickname()).isEqualTo("김사정");
    assertThat(row.confirmedMinAmount()).isEqualTo(5_000_000L);
    assertThat(row.confirmedMaxAmount()).isEqualTo(8_000_000L);
    assertThat(row.rating()).isEqualByComparingTo(new BigDecimal("4.30"));
  }

  @Test
  @DisplayName("ACCEPTED 제안 없음 — 확정 필드 4개는 null이고 proposalCount는 REJECTED만 제외한다")
  void nullWhenNoAcceptedProposal() {
    UUID userId = UUID.randomUUID();
    UUID reportId =
        saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.CANCER_DIAGNOSIS, "NL-1", null, null);
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.SENT, null, null);
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.COUNSELING, null, null);
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.REJECTED, null, null);
    flushAndClear();

    Page<ReportCardRow> page = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    ReportCardRow row = page.getContent().get(0);
    assertThat(row.proposalCount()).isEqualTo(2L); // SENT + COUNSELING (REJECTED 제외)
    assertThat(row.reviewedAt()).isNull();
    assertThat(row.adjusterNickname()).isNull();
    assertThat(row.confirmedMinAmount()).isNull();
    assertThat(row.confirmedMaxAmount()).isNull();
    assertThat(row.rating()).isNull();
  }

  // --- 시드 헬퍼 ---

  private UUID saveReport(UUID userId, ReportStatus status, AccidentType accidentType, String caseNo,
      Long claimMin, Long claimMax) {
    Report report = Report.createPending(userId, null, null, accidentType, "질문", caseNo);
    ReflectionTestUtils.setField(report, "status", status);
    ReflectionTestUtils.setField(report, "claimedMinAmount", claimMin);
    ReflectionTestUtils.setField(report, "claimedMaxAmount", claimMax);
    return reportRepository.save(report).getId();
  }

  private void saveReview(UUID reportId, UUID adjusterId, ReviewStatus status, Long estMin, Long estMax) {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "status", status);
    ReflectionTestUtils.setField(review, "estimateMinAmount", estMin);
    ReflectionTestUtils.setField(review, "estimateMaxAmount", estMax);
    reportReviewRepository.save(review);
  }

  private UUID saveAdjuster(String nickname, BigDecimal ratingMean) {
    UUID adjusterId = userRepository.save(
        User.create(nickname, LocalDate.of(1990, 1, 1), "", null, Role.CERTIFICATED_ADJUSTER)).getId();
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", adjusterId);
    ReflectionTestUtils.setField(profile, "ratingMean", ratingMean);
    adjusterProfileRepository.save(profile);
    return adjusterId;
  }

  /** @CreatedDate(updatable=false)는 JPA로 못 바꾸므로 정렬 검증용으로 네이티브 UPDATE로 created_at을 고정한다. */
  private void overrideCreatedAt(UUID reportId, LocalDateTime createdAt) {
    entityManager.createNativeQuery("UPDATE reports SET created_at = :ts WHERE id = :id")
        .setParameter("ts", Timestamp.valueOf(createdAt))
        .setParameter("id", reportId)
        .executeUpdate();
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
