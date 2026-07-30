package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * 검수대기 summary 카운트(countPending·countPendingByAccidentTypeIn·countDueSoon) 실행 검증.
 * 검수 대기 풀은 AWAITING_INSPECTION + AWAITING_ADOPTION 두 상태이며(홈 대시보드 countPendingPool과 동일한 풀),
 * dueSoon(검수 SLA 임박)은 아직 검수를 받지 않은 AWAITING_INSPECTION만 대상임을 함께 고정한다.
 * @Transactional로 각 테스트 종료 시 롤백돼 전역 카운트가 테스트 간 오염되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PendingReviewCountExecutionTest {

  @Autowired
  private ReportRepository reportRepository;
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("countPending — 검수대기 풀은 AWAITING_INSPECTION + AWAITING_ADOPTION (그 외 상태 제외)")
  void countPendingCountsBothPoolStatuses() {
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "PC-1");
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "PC-2");
    saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "PC-3");
    saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "PC-4");
    saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "PC-5");
    saveReport(ReportStatus.COUNSELING, AccidentType.OTHER, "PC-6");   // 제외
    saveReport(ReportStatus.CLOSED, AccidentType.OTHER, "PC-7");       // 제외
    saveReport(ReportStatus.NOT_SELECTED, AccidentType.OTHER, "PC-8"); // 제외
    flushAndClear();

    assertThat(reportRepository.countPending()).isEqualTo(5L);
  }

  @Test
  @DisplayName("countPendingByAccidentTypeIn — 검수대기 풀(두 상태) 중 지정 사고유형만")
  void countPendingByAccidentTypeCountsBothPoolStatuses() {
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.TRAFFIC, "AT-1");
    saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.TRAFFIC, "AT-2");
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.FIRE, "AT-3");   // 유형 불일치 제외
    saveReport(ReportStatus.COUNSELING, AccidentType.TRAFFIC, "AT-4");         // 상태 제외
    flushAndClear();

    assertThat(reportRepository.countPendingByAccidentTypeIn(Set.of(AccidentType.TRAFFIC))).isEqualTo(2L);
  }

  @Test
  @DisplayName("countDueSoon — 검수 SLA 임박은 AWAITING_INSPECTION만 (검수 받은 AWAITING_ADOPTION 제외)")
  void countDueSoonCountsOnlyAwaitingInspection() {
    UUID inspection = saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "DS-1");
    UUID adoption = saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "DS-2");
    flushAndClear();
    LocalDateTime old = LocalDateTime.now().minusDays(10);
    overrideCreatedAt(inspection, old);
    overrideCreatedAt(adoption, old);
    flushAndClear();

    LocalDateTime dueSoonThreshold = LocalDateTime.now().minusDays(6);
    assertThat(reportRepository.countDueSoon(dueSoonThreshold)).isEqualTo(1L);
  }

  // --- 시드 헬퍼 ---

  private UUID saveReport(ReportStatus status, AccidentType accidentType, String caseNo) {
    Report report = Report.createPending(UUID.randomUUID(), null, null, accidentType, "질문", caseNo);
    ReflectionTestUtils.setField(report, "status", status);
    return reportRepository.save(report).getId();
  }

  /** @CreatedDate(updatable=false)는 JPA로 못 바꾸므로 네이티브 UPDATE로 created_at을 과거로 고정한다. */
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
