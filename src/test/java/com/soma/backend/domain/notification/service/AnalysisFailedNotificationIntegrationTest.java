package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.service.AnalysisFailureNotificationSweeper;
import com.soma.backend.infra.fcm.FcmService;

/**
 * ANALYSIS_FAILED 알림 스윕의 실제 커밋 경로 통합 테스트(design.md §15 Q8·Q9).
 *
 * <p>스윕 트랜잭션 커밋 → AFTER_COMMIT 리스너 → 인앱 행(REQUIRES_NEW) + FCM까지를 한 번에 태운다.
 * 커밋이 일어나야 리스너가 발화하므로 클래스에 {@code @Transactional}을 두지 않고, 커밋한 데이터는
 * {@code @AfterEach}에서 지운다. 스윕은 테스트 프로파일에서 꺼져 있으므로 프로퍼티로 켜되, 스케줄러가
 * 검증 도중 끼어들지 않도록 주기를 1시간으로 늘린다(테스트는 sweep()을 직접 호출한다).
 */
@SpringBootTest(properties = {
    "app.report.analysis-failure-sweep-enabled=true",
    "app.report.analysis-failure-sweep-interval-ms=3600000"
})
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("분석 실패 알림 스윕 통합 테스트")
class AnalysisFailedNotificationIntegrationTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.now().minusHours(1);

  @Autowired
  private AnalysisFailureNotificationSweeper sweeper;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private NotificationRepository notificationRepository;
  @Autowired
  private NotificationSettingRepository notificationSettingRepository;
  @Autowired
  private DeviceTokenRepository deviceTokenRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private FcmService fcmService;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "SWP-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    deviceTokenRepository.save(DeviceToken.create(ownerId, "fcm-analysis-failed", Platform.ANDROID));
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures");
    notificationRepository.deleteAll();
    deviceTokenRepository.deleteAll();
    notificationSettingRepository.deleteAll();
    reportRepository.deleteAll();
  }

  private void insertFailure(String failureClass, boolean terminal) {
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, ?, 'ValueError', 3, ?, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), failureClass, terminal, FAILED_AT, FAILED_AT);
  }

  private List<Notification> notificationsOfOwner() {
    return notificationRepository
        .findByUserIdOrderByCreatedAtDesc(ownerId, PageRequest.of(0, 10)).getContent();
  }

  @Test
  @DisplayName("Q8 — 스윕을 2회 연속 실행해도 인앱 알림은 1건, 푸시도 1회다(analysis_failure_notified_at 가드)")
  void twoSweepsNotifyOnce() {
    // Given
    insertFailure("unreadable_file", true);

    // When
    sweeper.sweep();
    sweeper.sweep();

    // Then
    List<Notification> notifications = notificationsOfOwner();
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.ANALYSIS_FAILED);
    assertThat(notifications.get(0).getTitle()).isEqualTo("문서 분석에 실패했어요");
    verify(fcmService, times(1)).send(anyList(), anyString(), anyString(), anyMap());
    // 통지 시각이 기록돼 다음 사이클의 조회 대상에서 빠진다.
    assertThat(reportRepository.findById(reportId).orElseThrow())
        .extracting(report -> ReflectionTestUtils.getField(report, "analysisFailureNotifiedAt"))
        .isNotNull();
  }

  @Test
  @DisplayName("Q9 — 알림 토글을 전부 꺼도 ANALYSIS_FAILED는 인앱·푸시 모두 발송된다(시스템 실패 통지)")
  void analysisFailedIgnoresToggles() {
    // Given: 수신 토글 전부 OFF
    NotificationSetting allOff = NotificationSetting.createDefault(ownerId);
    allOff.applyPatch(false, false, false, false, false, false, false, false, false, false);
    notificationSettingRepository.save(allOff);
    insertFailure("ocr_error", true);

    // When
    sweeper.sweep();

    // Then
    assertThat(notificationsOfOwner()).singleElement()
        .satisfies(notification ->
            assertThat(notification.getType()).isEqualTo(NotificationType.ANALYSIS_FAILED));
    verify(fcmService).send(anyList(), eq("문서 분석에 실패했어요"), anyString(), anyMap());
  }

  @Test
  @DisplayName("Q1 — terminal=false 행만 있으면 스윕이 아무것도 통지하지 않는다")
  void nonTerminalFailureIsNotNotified() {
    insertFailure("ocr_error", false);

    sweeper.sweep();

    assertThat(notificationsOfOwner()).isEmpty();
    verify(fcmService, never()).send(any(), any(), any(), any());
  }

  @Test
  @DisplayName("AI 초안이 생긴 리포트는 실패 행이 남아 있어도 통지하지 않는다(성공이 실패를 이긴다)")
  void recoveredReportIsNotNotified() {
    Report report = reportRepository.findById(reportId).orElseThrow();
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));
    reportRepository.save(report);
    insertFailure("ocr_error", true);

    sweeper.sweep();

    assertThat(notificationsOfOwner()).isEmpty();
    verify(fcmService, never()).send(any(), any(), any(), any());
  }

  @Test
  @DisplayName("사유가 혼재하면 대표 사유(MASKING_RESIDUAL) 문안으로 통지한다 — 재업로드를 요구하지 않는다")
  void mixedReasonsUseNeutralCopy() {
    insertFailure("unreadable_file", true);
    insertFailure("masking_residual", true);

    sweeper.sweep();

    assertThat(notificationsOfOwner()).singleElement().satisfies(notification -> {
      assertThat(notification.getTitle()).isEqualTo("문서를 검토 중입니다");
      assertThat(notification.getBody()).doesNotContain("다시 업로드");
    });
  }

  @Test
  @DisplayName("lookback(7일) 밖의 오래된 실패는 스윕 대상이 아니다")
  void staleFailureOutsideLookbackIsSkipped() {
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, 'ocr_error', 'ValueError', 3, true, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(),
        LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(30));

    sweeper.sweep();

    assertThat(notificationsOfOwner()).isEmpty();
  }
}
