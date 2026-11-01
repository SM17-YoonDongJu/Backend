package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.service.ReportAnalysisFailureNotificationSweeper;
import com.soma.backend.infra.fcm.FcmPushSender;

/**
 * 리포트 생성 실패 저널({@code ai.report_job_failures}, AI 012)이 아직 없는 환경에서 분석 실패 알림 스윕의 커밋 경로
 * 검증.
 *
 * <p>스윕은 이 변경에서 바깥 트랜잭션이 <b>쓰기</b>(통지 시각)까지 하는 유일한 경로다. 리포트 생성 저널 조회가 같은
 * 트랜잭션에서 실패해 삼켜지면 커밋 시 {@code UnexpectedRollbackException}으로 OCR 실패 통지까지 같이 사라진다.
 * 그래서 테이블을 실제로 지운 상태에서 OCR 실패가 끝까지(인앱·푸시·통지 시각 커밋) 나가는지 본다 — 리더가
 * REQUIRES_NEW로 떨어져 있지 않으면 이 테스트가 깨진다.
 *
 * <p>스프링 컨텍스트 설정은 {@link AnalysisFailedNotificationIntegrationTest}와 같게 맞춰 컨텍스트를 재사용한다
 * (스윕은 프로퍼티로 켜고 주기를 1시간으로 늘려 테스트가 직접 {@code sweep()}을 부른다). 클래스 종료 시 계약
 * 스키마를 원복한다.
 */
@SpringBootTest(properties = {
    "app.report.analysis-failure-sweep-enabled=true",
    "app.report.analysis-failure-sweep-interval-ms=3600000"
})
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", statements = "DROP TABLE IF EXISTS ai.report_job_failures",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@DisplayName("리포트 생성 실패 저널(012) 미적용 시 분석 실패 알림 스윕 통합 테스트")
class AnalysisFailedNotificationWithoutReportJobJournalIntegrationTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.now().minusHours(1);

  @Autowired
  private ReportAnalysisFailureNotificationSweeper sweeper;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private NotificationRepository notificationRepository;
  @Autowired
  private DeviceTokenRepository deviceTokenRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private MeterRegistry meterRegistry;

  @MockitoBean
  private FcmPushSender fcmPushSender;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    // 전제 확인 — @Sql의 DROP이 빠지면 이 테스트는 012가 있는 평범한 스윕 테스트가 되어 버린다.
    assertThat(jdbcTemplate.queryForObject("select to_regclass('ai.report_job_failures')::text", String.class))
        .isNull();
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "NRJ-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    deviceTokenRepository.save(DeviceToken.create(ownerId, "fcm-no-report-journal", Platform.ANDROID));
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures");
    notificationRepository.deleteAll();
    deviceTokenRepository.deleteAll();
    reportRepository.deleteAll();
  }

  private double counter(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  @DisplayName("리포트 생성 저널이 없어도 OCR 확정 실패는 인앱·푸시로 통지되고 통지 시각이 커밋된다(사이클 에러 없음)")
  void ocrFailureIsNotifiedWhileReportJobJournalIsMissing() {
    // Given
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, 'unreadable_file', 'ValueError', 1, true, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), FAILED_AT, FAILED_AT);
    double sweepErrorsBefore = counter("analysis.failure.sweep.errors");
    double degradedBefore = counter("analysis.journal.degraded", "journal", "report_job");

    // When
    sweeper.sweep();

    // Then
    List<Notification> notifications = notificationRepository
        .findByUserIdOrderByCreatedAtDesc(ownerId, PageRequest.of(0, 10)).getContent();
    assertThat(notifications).singleElement()
        .extracting(Notification::getType)
        .isEqualTo(NotificationType.ANALYSIS_FAILED);
    verify(fcmPushSender, times(1)).send(anyList(), anyString(), anyString(), anyMap());
    assertThat(reportRepository.findById(reportId).orElseThrow())
        .extracting(report -> ReflectionTestUtils.getField(report, "analysisFailureNotifiedAt"))
        .isNotNull();
    assertThat(counter("analysis.failure.sweep.errors")).isEqualTo(sweepErrorsBefore);
    assertThat(counter("analysis.journal.degraded", "journal", "report_job")).isGreaterThan(degradedBefore);
  }
}
