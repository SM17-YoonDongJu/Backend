package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;

/**
 * API#5 내 검수 내역(GET /adjusters/me/reviewed-reports)의 서비스 경로를 실제 test_db에서 실행한다(#411).
 *
 * <p>{@link ReviewedReportQueryServiceTest}는 리포지토리를 목킹해서, 상태별 건수 쿼리가 PostgreSQL에서 준비조차 되지
 * 않아(42P18) 이 API가 월 필터와 관계없이 500을 내던 결함을 놓쳤다. 여기서는 고객·리포트·검수를 시드하고
 * {@code getReviewedReports}를 그대로 태워 상단 통계·status_counts·목록 쿼리가 함께 실행되는지, status_counts가 월
 * 필터만 반영하고 status 탭 필터는 반영하지 않는지 확인한다. @Transactional로 각 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReviewedReportQueryServiceExecutionTest {

  private static final int CONFIRMED_MIN_AMOUNT = 1_000_000;
  private static final int CONFIRMED_MAX_AMOUNT = 2_000_000;

  @Autowired
  private ReviewedReportQueryService reviewedReportQueryService;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private UUID adjusterId;
  private UUID customerId;
  private UUID sentReportId;
  private UUID counselingReportId;
  private UUID rejectedReportId;
  private UUID acceptedReportId;

  /**
   * 대상 사정사의 검수 4건(상태마다 1건 — 9월 SENT·COUNSELING, 8월 REJECTED, 7월 ACCEPTED)과, 9월 SENT 리포트에 다른
   * 사정사가 낸 경쟁 검수 1건을 시드한다. 목록 쿼리가 reports·users를 inner join하므로 고객과 리포트 행도 만든다.
   */
  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
    User customer = User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, List.of("서울"));
    entityManager.persist(customer);
    customerId = customer.getId();

    sentReportId = saveReport();
    counselingReportId = saveReport();
    rejectedReportId = saveReport();
    acceptedReportId = saveReport();
    saveReview(sentReportId, adjusterId, ReviewStatus.SENT, LocalDateTime.of(2026, 9, 15, 10, 0, 0));
    saveReview(counselingReportId, adjusterId, ReviewStatus.COUNSELING, LocalDateTime.of(2026, 9, 20, 10, 0, 0));
    saveReview(rejectedReportId, adjusterId, ReviewStatus.REJECTED, LocalDateTime.of(2026, 8, 10, 10, 0, 0));
    saveReview(acceptedReportId, adjusterId, ReviewStatus.ACCEPTED, LocalDateTime.of(2026, 7, 5, 10, 0, 0));
    // 같은 리포트에 다른 사정사가 낸 경쟁 검수 — 대상 사정사의 건수·목록에 섞이면 안 된다.
    saveReview(sentReportId, UUID.randomUUID(), ReviewStatus.SENT, LocalDateTime.of(2026, 9, 15, 11, 0, 0));
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  @DisplayName("month 미지정 — 실DB에서 예외 없이 status_counts가 total→enum 순 전체 분포로 채워진다")
  void monthOmitted_runsOnPostgresAndFillsFullDistribution() {
    ReviewedReportListResponse response =
        reviewedReportQueryService.getReviewedReports(adjusterId, "ALL", null, PageRequest.of(0, 20));

    assertThat(response.statusCounts()).containsExactly(
        entry("total", 4), entry("SENT", 1), entry("COUNSELING", 1), entry("REJECTED", 1), entry("ACCEPTED", 1));
    assertThat(response.items()).extracting(ReviewedReportListResponse.Item::reportId)
        .containsExactly(counselingReportId, sentReportId, rejectedReportId, acceptedReportId);
    // status=ALL이면 목록 총건수와 status_counts.total이 같은 조건(사정사 + 전 기간)으로 센 값이라 같아야 한다.
    assertThat(response.totalElements()).isEqualTo(4L);
    assertThat(response.stats().totalCount()).isEqualTo(4L);
    assertThat(response.stats().consultationConvertedCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("month·status 지정 — status_counts는 월 필터만 반영하고 status 탭 필터는 반영하지 않는다")
  void monthAndStatusGiven_countsIgnoreStatusTab() {
    ReviewedReportListResponse response =
        reviewedReportQueryService.getReviewedReports(adjusterId, "SENT", "2026-09", PageRequest.of(0, 20));

    // 9월 검수는 SENT·COUNSELING 각 1건이다. SENT 탭을 골라도 배지는 9월 전체 분포를 보여야 한다.
    assertThat(response.statusCounts()).containsExactly(
        entry("total", 2), entry("SENT", 1), entry("COUNSELING", 1), entry("REJECTED", 0), entry("ACCEPTED", 0));
    // 목록과 총건수는 월 필터와 status 탭 필터를 둘 다 받는다. 본문이 있는 첫 페이지에서는 PageImpl이 총건수를
    // 본문 크기로 다시 맞추므로, count 쿼리 값을 그대로 보려면 빈 2페이지도 확인해야 한다(아래).
    assertThat(response.totalElements()).isEqualTo(1L);
    ReviewedReportListResponse emptySecondPage =
        reviewedReportQueryService.getReviewedReports(adjusterId, "SENT", "2026-09", PageRequest.of(1, 20));
    assertThat(emptySecondPage.items()).isEmpty();
    assertThat(emptySecondPage.totalElements()).isEqualTo(1L);
    assertThat(response.items()).singleElement().satisfies(item -> {
      assertThat(item.reportId()).isEqualTo(sentReportId);
      assertThat(item.status()).isEqualTo("SENT");
      assertThat(item.reviewedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 10, 0, 0));
      assertThat(item.accidentType()).isEqualTo(AccidentType.TRAFFIC.getValue());
      assertThat(item.region()).isEqualTo("서울");
      assertThat(item.confirmedMinAmount()).isEqualTo(CONFIRMED_MIN_AMOUNT);
      assertThat(item.confirmedMaxAmount()).isEqualTo(CONFIRMED_MAX_AMOUNT);
      assertThat(item.rating()).isNull();
    });
    assertThat(response.stats().monthlyReviewCount()).isEqualTo(2L);
    assertThat(response.stats().previousMonthReviewCount()).isEqualTo(1L);
    assertThat(response.stats().totalCount()).isEqualTo(4L);
  }

  @Test
  @DisplayName("case_no는 검수 등록 시각이 아니라 리포트 생성일 + 리포트 ID로 만든다(#413)")
  void caseNoUsesReportCreatedAtNotReviewedAt() {
    // 검수 등록일(2026-09-15)과 다른 날로 리포트 생성 시각을 고정한다 — 검수 시각을 잘못 쓰면 날짜가 달라진다.
    entityManager.createNativeQuery("UPDATE reports SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", LocalDateTime.of(2026, 6, 1, 10, 0, 0))
        .setParameter("id", sentReportId)
        .executeUpdate();

    ReviewedReportListResponse response =
        reviewedReportQueryService.getReviewedReports(adjusterId, "SENT", "2026-09", PageRequest.of(0, 20));

    assertThat(response.items()).singleElement().extracting(ReviewedReportListResponse.Item::caseNo)
        .isEqualTo("20260601-" + sentReportId.toString().substring(0, 8).toUpperCase(Locale.ROOT));
  }

  private UUID saveReport() {
    Report report = Report.createPending(
        customerId, null, null, AccidentType.TRAFFIC, null, "질문");
    return reportRepository.save(report).getId();
  }

  /**
   * 검수를 저장하고 상태와 등록 시각을 맞춘다. 상태는 도메인 전이로 만들고, created_at은 감사 필드(updatable=false)라
   * flush 뒤 네이티브 UPDATE로 덮어쓴다(ReportReviewRepositoryExecutionTest.saveProposal과 같은 방식). 확정 금액은
   * 목록 projection(estimate_min/max_amount)을 실데이터로 확인하려고 고정값을 넣는다.
   */
  private void saveReview(UUID reportId, UUID reviewerId, ReviewStatus status, LocalDateTime createdAt) {
    ReportReview review = reportReviewRepository.save(ReportReview.create(reportId, reviewerId));
    review.updateReviewContent(CONFIRMED_MIN_AMOUNT, CONFIRMED_MAX_AMOUNT, null, null, null, "검수 의견");
    switch (status) {
      case SENT -> {
        // 생성 직후 기본 상태라 전이가 없다.
      }
      case COUNSELING -> review.startCounseling();
      case ACCEPTED -> review.accept();
      case REJECTED -> review.reject();
      default -> throw new IllegalArgumentException("unsupported status: " + status);
    }
    entityManager.flush();
    entityManager.createNativeQuery("UPDATE report_reviews SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", review.getId())
        .executeUpdate();
  }
}
