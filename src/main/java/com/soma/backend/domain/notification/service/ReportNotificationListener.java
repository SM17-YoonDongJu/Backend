package com.soma.backend.domain.notification.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.event.AnalysisFailedEvent;
import com.soma.backend.domain.report.entity.event.ReportBlockedEvent;
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

  /**
   * 분석(OCR·AI) 확정 실패 통지. 실패 사유에 따라 문안을 나눈다 — 마스킹 잔류는 사용자 과실이 아니라
   * 시스템 한계라 "검토 중"으로 중립 서술하고 재업로드를 요구하지 않는다.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAnalysisFailed(AnalysisFailedEvent event) {
    String title = pushTitle(event.reason());
    String body = pushBody(event.reason());
    dispatch(event.userId(), NotificationType.ANALYSIS_FAILED, title, body,
        Map.of("type", NotificationType.ANALYSIS_FAILED.name(), "reportId", event.reportId().toString()));
  }

  /**
   * AI 입력 가드레일 차단 통지. 사유(오프토픽·PII 복호화 실패 등)를 구분하지 않는다 — AI가 reports 테이블로
   * 넘겨주지 않아 구분할 수단이 없다. 문구는 {@link ReportAnalysis#blocked()}의 것을 그대로 재사용해
   * 조회 API 응답과 푸시가 같은 문구를 쓰게 한다(단일 진실).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onReportBlocked(ReportBlockedEvent event) {
    String body = ReportAnalysis.blocked().failureMessage();
    dispatch(event.userId(), NotificationType.REPORT_BLOCKED, "처리할 수 없는 요청이에요", body,
        Map.of("type", NotificationType.REPORT_BLOCKED.name(), "reportId", event.reportId().toString()));
  }

  private String pushTitle(AnalysisFailureReason reason) {
    if (reason == AnalysisFailureReason.MASKING_RESIDUAL) {
      return "문서를 검토 중입니다";
    }
    return "문서 분석에 실패했어요";
  }

  private String pushBody(AnalysisFailureReason reason) {
    if (reason == AnalysisFailureReason.MASKING_RESIDUAL) {
      return "업로드하신 문서를 추가로 확인하고 있어요. 확인되면 알려드릴게요.";
    }
    if (reason == AnalysisFailureReason.UNREADABLE_FILE) {
      return "파일을 읽을 수 없어요. 다시 업로드해주세요.";
    }
    return "처리에 실패했어요. 확인 중이니 조금만 기다려주세요.";
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
