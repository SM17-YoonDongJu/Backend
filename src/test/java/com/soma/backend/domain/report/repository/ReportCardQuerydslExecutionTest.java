package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * findUserReportCards(QueryDSL, per-review) 실행 검증 — 실제 test_db에 시드 데이터를 넣고, report_reviews
 * 1건당 1행으로 펴지는지·소유자 스코프·status 필터·페이지네이션·리뷰별 필드(adjusterNickname·reviewedAt)와
 * 리포트 필드(offeredAmount·treatment·proposalCount=REJECTED 제외)를 확인한다. status의 CLOSED→MATCHED
 * 매핑은 응답 DTO(Card.from) 책임이라 여기선 원본 ReportStatus를 검증한다.
 * @Transactional로 각 테스트 종료 시 롤백돼 다른 실행 테스트를 오염시키지 않는다.
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
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("per-review·소유자 스코프 — 소유 리포트의 리뷰만 1건당 1행으로 반환(타인·무리뷰 리포트 제외)")
  void perReviewOwnerScope() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID a1 = saveReport(userA, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "OS-A1");
    saveReview(a1, saveUser("사정1"), ReviewStatus.SENT);
    saveReview(a1, saveUser("사정2"), ReviewStatus.COUNSELING);
    saveReport(userA, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "OS-A2"); // 리뷰 없음 → 행 없음
    UUID b1 = saveReport(userB, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "OS-B1");
    saveReview(b1, saveUser("사정3"), ReviewStatus.SENT);
    flushAndClear();

    Page<ReportCardRow> pageA = reportRepository.findUserReportCards(userA, null, PageRequest.of(0, 10));

    assertThat(pageA.getTotalElements()).isEqualTo(2); // a1의 리뷰 2건(무리뷰 리포트·타인 리뷰 제외)
    assertThat(pageA.getContent()).extracting(ReportCardRow::reportId).containsOnly(a1);
    assertThat(pageA.getContent()).extracting(ReportCardRow::adjusterNickname)
        .containsExactlyInAnyOrder("사정1", "사정2");
  }

  @Test
  @DisplayName("status 필터 — 지정 리포트 상태의 리뷰만, 필터 없으면 전 상태")
  void filtersByReportStatus() {
    UUID userId = UUID.randomUUID();
    UUID r1 = saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "SF-1");
    saveReview(r1, saveUser("a"), ReviewStatus.SENT);
    UUID r2 = saveReport(userId, ReportStatus.COUNSELING, AccidentType.OTHER, "SF-2");
    saveReview(r2, saveUser("b"), ReviewStatus.SENT);
    UUID r3 = saveReport(userId, ReportStatus.CLOSED, AccidentType.OTHER, "SF-3");
    saveReview(r3, saveUser("c"), ReviewStatus.ACCEPTED);
    flushAndClear();

    Page<ReportCardRow> all = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 10));
    assertThat(all.getTotalElements()).isEqualTo(3);

    Page<ReportCardRow> onlyCounseling =
        reportRepository.findUserReportCards(userId, ReportStatus.COUNSELING, PageRequest.of(0, 10));
    assertThat(onlyCounseling.getContent()).extracting(ReportCardRow::reportId).containsExactly(r2);
    assertThat(onlyCounseling.getContent().get(0).status()).isEqualTo(ReportStatus.COUNSELING);
  }

  @Test
  @DisplayName("리뷰별·리포트별 필드 — 모든 리뷰(REJECTED 포함)가 행이 되고 리포트값·리뷰값이 맞게 채워진다")
  void mapsReviewAndReportFields() {
    UUID userId = UUID.randomUUID();
    UUID reportId = saveReport(userId, ReportStatus.CLOSED, AccidentType.TRAFFIC, "AC-1");
    setReportExtras(reportId, 1_000_000, 2_000_000, 8_500_000, "후유장해");
    saveReview(reportId, saveUser("김사정"), ReviewStatus.ACCEPTED);
    saveReview(reportId, saveUser("이사정"), ReviewStatus.SENT);
    saveReview(reportId, saveUser("박사정"), ReviewStatus.REJECTED);
    flushAndClear();

    Page<ReportCardRow> page = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(3); // 리뷰 3건 모두(REJECTED 포함)
    assertThat(page.getContent()).allSatisfy(row -> {
      assertThat(row.reportId()).isEqualTo(reportId);
      assertThat(row.status()).isEqualTo(ReportStatus.CLOSED);
      assertThat(row.accidentType()).isEqualTo(AccidentType.TRAFFIC);
      assertThat(row.caseNo()).isEqualTo("AC-1");
      assertThat(row.claimedMinAmount()).isEqualTo(1_000_000);
      assertThat(row.claimedMaxAmount()).isEqualTo(2_000_000);
      assertThat(row.proposalCount()).isEqualTo(2L); // ACCEPTED + SENT (REJECTED 제외)
      assertThat(row.offeredAmount()).isEqualTo(8_500_000);
      assertThat(row.treatment()).isEqualTo("후유장해");
      assertThat(row.reviewedAt()).isNotNull();
    });
    assertThat(page.getContent()).extracting(ReportCardRow::adjusterNickname)
        .containsExactlyInAnyOrder("김사정", "이사정", "박사정");
  }

  @Test
  @DisplayName("페이지네이션 — 리포트 created_at DESC 정렬 + total/hasNext(리뷰 행 기준)")
  void paginatesNewestFirst() {
    UUID userId = UUID.randomUUID();
    List<UUID> ids = new ArrayList<>();
    LocalDateTime base = LocalDateTime.of(2026, 7, 1, 9, 0);
    for (int idx = 0; idx < 5; idx++) {
      UUID reportId = saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "PG-" + idx);
      saveReview(reportId, saveUser("adj-" + idx), ReviewStatus.SENT);
      ids.add(reportId);
    }
    entityManager.flush();
    for (int idx = 0; idx < 5; idx++) {
      overrideCreatedAt(ids.get(idx), base.plusMinutes(idx));
    }
    flushAndClear();

    Page<ReportCardRow> page0 = reportRepository.findUserReportCards(userId, null, PageRequest.of(0, 2));
    assertThat(page0.getTotalElements()).isEqualTo(5); // 리포트 5개 × 리뷰 1건
    assertThat(page0.getTotalPages()).isEqualTo(3);
    assertThat(page0.hasNext()).isTrue();
    assertThat(page0.getContent()).extracting(ReportCardRow::reportId)
        .containsExactly(ids.get(4), ids.get(3)); // 최신 리포트 먼저

    Page<ReportCardRow> page2 = reportRepository.findUserReportCards(userId, null, PageRequest.of(2, 2));
    assertThat(page2.getContent()).extracting(ReportCardRow::reportId).containsExactly(ids.get(0));
    assertThat(page2.hasNext()).isFalse();
  }

  @Test
  @DisplayName("findReportsWithProposals — REJECTED 제외 리뷰만 per-review로, 전부 REJECTED·무리뷰·타인은 제외")
  void findReportsWithProposalsExcludesRejected() {
    UUID userId = UUID.randomUUID();
    UUID r1 = saveReport(userId, ReportStatus.COUNSELING, AccidentType.OTHER, "RP-1");
    saveReview(r1, saveUser("사정1"), ReviewStatus.SENT);
    saveReview(r1, saveUser("사정2"), ReviewStatus.REJECTED); // 제외
    UUID r2 = saveReport(userId, ReportStatus.CLOSED, AccidentType.OTHER, "RP-2");
    saveReview(r2, saveUser("사정3"), ReviewStatus.ACCEPTED);
    UUID r3 = saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "RP-3");
    saveReview(r3, saveUser("사정4"), ReviewStatus.REJECTED); // 전부 REJECTED → 행 없음
    saveReport(userId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "RP-4"); // 무리뷰 → 행 없음
    UUID other = saveReport(UUID.randomUUID(), ReportStatus.COUNSELING, AccidentType.OTHER, "RP-5");
    saveReview(other, saveUser("사정5"), ReviewStatus.SENT); // 타인 → 제외
    flushAndClear();

    Page<ReportCardRow> page = reportRepository.findReportsWithProposals(userId, PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(2); // r1 SENT + r2 ACCEPTED
    assertThat(page.getContent()).extracting(ReportCardRow::reportId).containsExactlyInAnyOrder(r1, r2);
    assertThat(page.getContent()).extracting(ReportCardRow::adjusterNickname)
        .containsExactlyInAnyOrder("사정1", "사정3");
  }

  // --- 시드 헬퍼 ---

  private UUID saveReport(UUID userId, ReportStatus status, AccidentType accidentType, String caseNo) {
    Report report = Report.createPending(userId, null, null, accidentType, "질문", caseNo);
    ReflectionTestUtils.setField(report, "status", status);
    return reportRepository.save(report).getId();
  }

  private void setReportExtras(UUID reportId, Integer claimMin, Integer claimMax, Integer offered, String treatment) {
    Report report = reportRepository.findById(reportId).orElseThrow();
    ReflectionTestUtils.setField(report, "claimedMinAmount", claimMin);
    ReflectionTestUtils.setField(report, "claimedMaxAmount", claimMax);
    ReflectionTestUtils.setField(report, "offeredAmount", offered);
    ReflectionTestUtils.setField(report, "treatment", treatment);
    reportRepository.save(report);
  }

  private void saveReview(UUID reportId, UUID adjusterId, ReviewStatus status) {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "status", status);
    reportReviewRepository.save(review);
  }

  private UUID saveUser(String nickname) {
    return userRepository.save(
        User.create(nickname, LocalDate.of(1990, 1, 1), "", null, Role.CERTIFICATED_ADJUSTER, null)).getId();
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
