package com.soma.backend.domain.notification.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.event.AnalysisFailedEvent;
import com.soma.backend.domain.report.entity.event.ReportBlockedEvent;
import com.soma.backend.domain.report.entity.event.ReportNeedsReuploadEvent;
import com.soma.backend.domain.report.entity.event.ReviewProposalReceivedEvent;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.service.TerminalFailureJournalReader;

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
  private final TerminalFailureJournalReader terminalFailureJournalReader;
  private final ReportAttachmentRepository reportAttachmentRepository;

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

  /**
   * OCR 품질 미달(재업로드 필요) 통지. 청구 fan-in으로 걸린 경우(#67)는 저널({@code ai.ocr_job_failures})에
   * 문서가 특정되므로 그 문서명을 문구에 넣는다 — "어느 문서인지" 정할 수 있으면 정하는 게 사용자에게
   * 더 도움이 된다. 문서를 하나로 못 좁히면(개별 문서 품질 게이트라 저널 흔적이 없음, 저널 조회 실패,
   * 여러 문서가 걸림) {@link ReportAnalysis#needsReupload()}의 일반 문구로 degrade한다 — 조회 API의
   * {@code failure_message}와 이 경우엔 같은 문구를 쓴다(단일 진실).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onReportNeedsReupload(ReportNeedsReuploadEvent event) {
    ReportAttachment specificDocument = resolveSingleFailedDocument(event.reportId());
    String generic = ReportAnalysis.needsReupload().failureMessage();
    String body = specificDocument == null
        ? generic
        : "업로드하신 [" + specificDocument.getName() + "] 문서를 알아보기 어려워요. 다시 촬영해서 올려주세요.";
    Map<String, String> data = specificDocument == null
        ? Map.of("type", NotificationType.REPORT_NEEDS_REUPLOAD.name(), "reportId", event.reportId().toString())
        : Map.of("type", NotificationType.REPORT_NEEDS_REUPLOAD.name(), "reportId", event.reportId().toString(),
            "attachmentId", specificDocument.getId().toString());
    dispatch(event.userId(), NotificationType.REPORT_NEEDS_REUPLOAD, "문서를 다시 올려주세요", body, data);
  }

  /**
   * 청구 fan-in으로 걸린 NEEDS_REUPLOAD의 확정 실패 저널을 조회해, 문서 정확히 한 건이 특정되고 첨부
   * 이름까지 있으면 그 첨부를 반환한다. 저널 조회 실패·문서 미특정·복수 문서(대표 하나를 고를 근거가
   * 없음)는 전부 {@code null} — 호출자가 일반 문구로 degrade한다({@code TerminalFailureJournalReader}가
   * REQUIRES_NEW라 AFTER_COMMIT처럼 활성 트랜잭션이 없는 지점에서도 안전하게 호출된다).
   */
  private ReportAttachment resolveSingleFailedDocument(UUID reportId) {
    List<OcrJobFailureView> failures;
    try {
      failures = terminalFailureJournalReader.findTerminalFailures(List.of(reportId))
          .getOrDefault(reportId, List.of());
    } catch (DataAccessException ex) {
      return null;
    }
    if (failures.size() != 1 || failures.getFirst().getAttachmentId() == null) {
      return null;
    }
    return reportAttachmentRepository.findById(failures.getFirst().getAttachmentId()).orElse(null);
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
