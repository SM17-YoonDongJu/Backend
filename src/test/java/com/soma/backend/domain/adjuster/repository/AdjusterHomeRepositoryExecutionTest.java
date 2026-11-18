package com.soma.backend.domain.adjuster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * 사정사 홈 QueryDSL 읽기 모델이 실제 PostgreSQL에서 SQL로 번역·실행되는지 검증한다
 * (users LEFT JOIN adjuster_profiles + coalesce projection, reports/report_reviews 조인·enum IN·컨버터 projection).
 * 데이터가 없어도 SQL은 준비·실행되므로 번역 불가·문법 오류가 있으면 여기서 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdjusterHomeRepositoryExecutionTest {

  @Autowired
  private AdjusterHomeRepository adjusterHomeRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("findAdjusterIdentity — users LEFT JOIN adjuster_profiles + coalesce projection이 실행된다")
  void findAdjusterIdentityExecutes() {
    AdjusterIdentityRow row = adjusterHomeRepository.findAdjusterIdentity(UUID.randomUUID());

    assertThat(row).isNull();
  }

  @Test
  @DisplayName("대기 풀·진행 중·이번 달 완료 카운트 쿼리가 실행된다(빈 DB에서 0)")
  void countsExecute() {
    UUID adjusterId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    assertThat(adjusterHomeRepository.countPendingPoolNotHeldBy(adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countPendingPoolNewNotHeldBy(now.minusHours(24), adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countInProgress(adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countCompletedBetween(adjusterId, now.minusDays(30), now)).isZero();
  }

  @Test
  @DisplayName("findInProgressCases — report_reviews·reports 조인 + status IN 조회가 실행된다")
  void findInProgressCasesExecutes() {
    List<InProgressCaseRow> rows = adjusterHomeRepository.findInProgressCases(UUID.randomUUID(), 5);

    assertThat(rows).isEmpty();
  }

  @Test
  @Transactional
  @DisplayName("findInProgressCases — 진행 중 사건 행은 검수가 아니라 리포트의 생성 시각을 담는다(사건번호 파생 근거, #413)")
  void findInProgressCasesCarriesReportCreatedAt() {
    UUID adjusterId = UUID.randomUUID();
    Report report = reportRepository.save(Report.createPending(
        UUID.randomUUID(), null, null, AccidentType.TRAFFIC, null, "질문"));
    reportReviewRepository.save(ReportReview.create(report.getId(), adjusterId));
    entityManager.flush();
    // 검수는 지금 시각으로 두고 리포트만 다른 날로 고정해, 두 created_at이 섞이면 드러나게 한다.
    LocalDateTime reportCreatedAt = LocalDateTime.of(2026, 4, 2, 11, 0);
    entityManager.createNativeQuery("UPDATE reports SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", reportCreatedAt)
        .setParameter("id", report.getId())
        .executeUpdate();

    List<InProgressCaseRow> rows = adjusterHomeRepository.findInProgressCases(adjusterId, 5);

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.reportId()).isEqualTo(report.getId());
      assertThat(row.reportCreatedAt()).isEqualTo(reportCreatedAt);
    });
  }
}
