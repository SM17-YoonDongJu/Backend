package com.soma.backend.domain.notification.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.report.entity.event.ReviewProposalReceivedEvent;

/**
 * report 도메인 사실 이벤트를 인앱 알림 + 푸시로 변환하는 notification 구독자. 원 트랜잭션이 커밋된 뒤
 * (AFTER_COMMIT) 발화하므로 부수효과는 best-effort(at-most-once)이며, 여기서 실패해도 이미 커밋된
 * 원 도메인 결과에는 영향이 없다.
 *
 * <p>인앱 저장(REQUIRES_NEW)은 {@link NotificationDispatchService}에 위임한다 — AFTER_COMMIT엔 활성 트랜잭션이
 * 없어 새 트랜잭션이 필요하고, self-invocation 프록시 함정을 피하려면 별도 빈이어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportNotificationListener {

  private final NotificationDispatchService notificationDispatchService;
  private final PushNotificationService pushNotificationService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProposalReceived(ReviewProposalReceivedEvent event) {
    dispatch(event.userId(), NotificationType.RECEIVED_PROPOSAL,
        "새로운 제안이 도착했어요",
        "손해사정사가 새로운 검수 제안을 보냈어요. 제안을 비교해보세요.",
        Map.of("type", NotificationType.RECEIVED_PROPOSAL.name(), "reportId", event.reportId().toString()));
  }

  private void dispatch(UUID userId, NotificationType type, String title, String body, Map<String, String> data) {
    try {
      boolean pushAllowed = notificationDispatchService.record(userId, type, title, body);
      if (pushAllowed) {
        pushNotificationService.sendToUser(userId, title, body, data);
      }
    } catch (RuntimeException ex) {
      log.warn("알림 발송 실패(격리) — userId={}, type={}", userId, type, ex);
    }
  }
}
