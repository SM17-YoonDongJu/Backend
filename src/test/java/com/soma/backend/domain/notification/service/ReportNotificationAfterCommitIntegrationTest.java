package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;
import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.infra.fcm.FcmService;

/**
 * RECEIVED_PROPOSAL 알림 producer 배선(AFTER_COMMIT + REQUIRES_NEW)의 실제 test_db 통합 테스트.
 *
 * <p>Mockito 단위 테스트로는 확인할 수 없는 "원 트랜잭션 커밋 후 리스너 발화 → 새 트랜잭션에서 인앱 insert"를
 * 실제 커밋 경로로 검증한다. 이 때문에 클래스에 {@code @Transactional}을 두지 않는다 —
 * 테스트 트랜잭션으로 감싸면 커밋이 일어나지 않아 AFTER_COMMIT 리스너가 절대 발화하지 않기 때문이다.
 * 커밋한 데이터는 {@code @AfterEach}에서 정리한다. FCM I/O는 {@link FcmService}를 @MockitoBean으로 대체한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("RECEIVED_PROPOSAL AFTER_COMMIT 통합 테스트")
class ReportNotificationAfterCommitIntegrationTest {

  private static final String EXPECTED_TITLE = "새로운 제안이 도착했어요";

  @Autowired
  private ReportReviewCommandService reportReviewCommandService;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private NotificationRepository notificationRepository;
  @Autowired
  private NotificationSettingRepository notificationSettingRepository;
  @Autowired
  private DeviceTokenRepository deviceTokenRepository;

  @MockitoBean
  private FcmService fcmService;

  private User customer;
  private User adjuster;
  private Report report;

  @AfterEach
  void cleanUp() {
    // @Transactional 롤백이 없으므로 커밋된 데이터를 직접 정리한다(엔티티 간 FK 제약 미매핑이라 순서 무관).
    notificationRepository.deleteAll();
    deviceTokenRepository.deleteAll();
    notificationSettingRepository.deleteAll();
    reportReviewRepository.deleteAll();
    reportRepository.deleteAll();
    userRepository.deleteAll();
  }

  private void seedCustomerAdjusterReport(ReportStatus status) {
    customer = userRepository.save(User.create("고객", LocalDate.of(1990, 1, 1), "F", null, Role.USER, null));
    adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER, null));
    Report pending = Report.createPending(customer.getId(), null, null,
        AccidentType.MEDICAL_INDEMNITY, "질문", "INT-" + UUID.randomUUID().toString().substring(0, 12));
    if (status != ReportStatus.AWAITING_INSPECTION) {
      ReflectionTestUtils.setField(pending, "status", status);
    }
    report = reportRepository.save(pending);
  }

  private ReviewReportRequest emptyReviewRequest() {
    return new ReviewReportRequest(
        1_000_000L, 3_000_000L, List.of(), List.of(), List.of(), List.of(), "통합 테스트 검수 의견");
  }

  private List<Notification> notificationsOfCustomer() {
    return notificationRepository
        .findByUserIdOrderByCreatedAtDesc(customer.getId(), PageRequest.of(0, 10)).getContent();
  }

  @Test
  @DisplayName("검수 반영이 커밋되면 인앱 RECEIVED_PROPOSAL 1행이 저장되고 FCM 발송 경로가 호출된다")
  void reviewCommitPersistsInAppNotificationAndSendsPush() {
    seedCustomerAdjusterReport(ReportStatus.AWAITING_INSPECTION);
    deviceTokenRepository.save(DeviceToken.create(customer.getId(), "fcm-token-on", Platform.ANDROID));

    reportReviewCommandService.review(adjuster.getId(), report.getId(), emptyReviewRequest());

    List<Notification> notifications = notificationsOfCustomer();
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.RECEIVED_PROPOSAL);
    assertThat(notifications.get(0).getUserId()).isEqualTo(customer.getId());
    assertThat(notifications.get(0).getTitle()).isEqualTo(EXPECTED_TITLE);
    verify(fcmService).send(anyList(), eq(EXPECTED_TITLE), anyString(), anyMap());
  }

  @Test
  @DisplayName("푸시 토글 OFF 사용자는 인앱 1행은 저장되지만 FCM은 호출되지 않는다")
  void reviewCommitPersistsInAppButSkipsPushWhenToggleOff() {
    seedCustomerAdjusterReport(ReportStatus.AWAITING_INSPECTION);
    NotificationSetting off = NotificationSetting.createDefault(customer.getId());
    ReflectionTestUtils.setField(off, "receivedProposal", false);
    notificationSettingRepository.save(off);
    deviceTokenRepository.save(DeviceToken.create(customer.getId(), "fcm-token-off", Platform.ANDROID));

    reportReviewCommandService.review(adjuster.getId(), report.getId(), emptyReviewRequest());

    List<Notification> notifications = notificationsOfCustomer();
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.RECEIVED_PROPOSAL);
    verify(fcmService, never()).send(any(), any(), any(), any());
  }

  @Test
  @DisplayName("검수 반영이 예외로 롤백되면(CLOSED) AFTER_COMMIT 미발화로 인앱 알림이 저장되지 않는다")
  void reviewRollbackDoesNotPersistNotification() {
    seedCustomerAdjusterReport(ReportStatus.CLOSED);
    deviceTokenRepository.save(DeviceToken.create(customer.getId(), "fcm-token-rollback", Platform.ANDROID));

    assertThatThrownBy(() ->
        reportReviewCommandService.review(adjuster.getId(), report.getId(), emptyReviewRequest()))
        .isInstanceOf(BusinessException.class);

    assertThat(notificationsOfCustomer()).isEmpty();
    verify(fcmService, never()).send(any(), any(), any(), any());
  }
}
