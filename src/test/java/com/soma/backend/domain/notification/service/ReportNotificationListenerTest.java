package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.OcrJobFailureViewFixture;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.event.AnalysisFailedEvent;
import com.soma.backend.domain.report.entity.event.ReportBlockedEvent;
import com.soma.backend.domain.report.entity.event.ReportNeedsReuploadEvent;
import com.soma.backend.domain.report.entity.event.ReviewProposalReceivedEvent;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.service.TerminalFailureJournalReader;

/**
 * ReportNotificationListener 단위 테스트.
 * AFTER_COMMIT 리스너는 record()로 인앱 저장·토글을 위임하고, 토글 ON일 때만 푸시하며,
 * 부수효과 실패(record·push 예외)는 삼켜 원 트랜잭션(이미 커밋)에 전파하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportNotificationListener 단위 테스트")
class ReportNotificationListenerTest {

  private static final String EXPECTED_TITLE = "새로운 제안이 도착했어요";
  private static final String EXPECTED_BODY = "손해사정사가 새로운 검수 제안을 보냈어요. 제안을 비교해보세요.";

  @InjectMocks
  private ReportNotificationListener listener;
  @Mock
  private NotificationDispatchService notificationDispatchService;
  @Mock
  private PushNotificationService pushNotificationService;
  @Mock
  private TerminalFailureJournalReader terminalFailureJournalReader;
  @Mock
  private ReportAttachmentRepository reportAttachmentRepository;

  @Test
  @DisplayName("record가 true면 sendToUser로 푸시하며 data{type,reportId}를 담아 보낸다")
  void onProposalReceivedPushesWhenAllowed() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.RECEIVED_PROPOSAL), anyString(), anyString())).willReturn(true);

    // When
    listener.onProposalReceived(new ReviewProposalReceivedEvent(userId, reportId, adjusterId));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq(EXPECTED_TITLE), eq(EXPECTED_BODY), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "RECEIVED_PROPOSAL")
        .containsEntry("reportId", reportId.toString());
  }

  @Test
  @DisplayName("record가 false면 sendToUser를 호출하지 않는다(인앱만 저장됨)")
  void onProposalReceivedSkipsPushWhenNotAllowed() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(false);

    // When
    listener.onProposalReceived(new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID()));

    // Then
    verify(pushNotificationService, never()).sendToUser(any(), any(), any(), any());
  }

  @Test
  @DisplayName("sendToUser가 예외를 던져도 dispatch가 삼켜 예외가 전파되지 않는다")
  void onProposalReceivedSwallowsPushException() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);
    willThrow(new RuntimeException("FCM down"))
        .given(pushNotificationService).sendToUser(any(), any(), any(), any());

    // When & Then
    assertThatCode(() -> listener.onProposalReceived(
        new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("ANALYSIS_FAILED — unreadable_file은 재업로드를 안내하는 문안으로 발송된다")
  void onAnalysisFailedUsesReuploadCopyForUnreadableFile() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.ANALYSIS_FAILED), anyString(), anyString())).willReturn(true);

    // When
    listener.onAnalysisFailed(
        new AnalysisFailedEvent(userId, reportId, AnalysisFailureReason.UNREADABLE_FILE));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq("문서 분석에 실패했어요"), eq("파일을 읽을 수 없어요. 다시 업로드해주세요."), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "ANALYSIS_FAILED")
        .containsEntry("reportId", reportId.toString());
  }

  @Test
  @DisplayName("ANALYSIS_FAILED — masking_residual은 재업로드를 요구하지 않는 중립 문안으로 발송된다(사용자 과실 아님)")
  void onAnalysisFailedUsesNeutralCopyForMaskingResidual() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);

    // When
    listener.onAnalysisFailed(
        new AnalysisFailedEvent(userId, UUID.randomUUID(), AnalysisFailureReason.MASKING_RESIDUAL));

    // Then
    ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(pushNotificationService).sendToUser(eq(userId), title.capture(), body.capture(), any());
    assertThat(title.getValue()).isEqualTo("문서를 검토 중입니다");
    assertThat(body.getValue()).isEqualTo("업로드하신 문서를 추가로 확인하고 있어요. 확인되면 알려드릴게요.");
    assertThat(body.getValue()).doesNotContain("다시 업로드");
  }

  @Test
  @DisplayName("ANALYSIS_FAILED — 그 외 사유는 확인 중 문안이며 컴플라이언스 금칙 표현이 없다")
  void onAnalysisFailedUsesHoldCopyForOtherReasons() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);

    // When
    listener.onAnalysisFailed(new AnalysisFailedEvent(userId, UUID.randomUUID(), AnalysisFailureReason.OCR_ERROR));

    // Then
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(pushNotificationService).sendToUser(eq(userId), eq("문서 분석에 실패했어요"), body.capture(), any());
    assertThat(body.getValue()).isEqualTo("처리에 실패했어요. 확인 중이니 조금만 기다려주세요.");
    // 보상금액·법률 자문 성격 표현은 알림 문안에도 들어가면 안 된다.
    assertThat(body.getValue()).doesNotContain("보상", "보험금", "법적", "청구");
  }

  @Test
  @DisplayName("ANALYSIS_FAILED — FCM이 실패해도 예외가 전파되지 않는다(스윕 커밋에 영향 없음)")
  void onAnalysisFailedSwallowsPushException() {
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);
    willThrow(new RuntimeException("FCM down"))
        .given(pushNotificationService).sendToUser(any(), any(), any(), any());

    assertThatCode(() -> listener.onAnalysisFailed(new AnalysisFailedEvent(
        UUID.randomUUID(), UUID.randomUUID(), AnalysisFailureReason.UNKNOWN)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("record가 예외를 던져도 dispatch가 삼켜 예외가 전파되지 않고 푸시도 없다")
  void onProposalReceivedSwallowsRecordException() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString()))
        .willThrow(new RuntimeException("DB down"));

    // When & Then
    assertThatCode(() -> listener.onProposalReceived(
        new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
    verify(pushNotificationService, never()).sendToUser(any(), any(), any(), any());
  }

  @Test
  @DisplayName("REPORT_BLOCKED — ReportAnalysis.blocked()와 동일한 문구로 발송된다(조회 API·푸시 단일 진실)")
  void onReportBlockedUsesSameCopyAsAnalysisStatus() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.REPORT_BLOCKED), anyString(), anyString())).willReturn(true);

    // When
    listener.onReportBlocked(new ReportBlockedEvent(userId, reportId));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq("처리할 수 없는 요청이에요"),
        eq(ReportAnalysis.blocked().failureMessage()), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "REPORT_BLOCKED")
        .containsEntry("reportId", reportId.toString());
  }

  @Test
  @DisplayName("REPORT_BLOCKED — FCM이 실패해도 예외가 전파되지 않는다(스윕 커밋에 영향 없음)")
  void onReportBlockedSwallowsPushException() {
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);
    willThrow(new RuntimeException("FCM down"))
        .given(pushNotificationService).sendToUser(any(), any(), any(), any());

    assertThatCode(() -> listener.onReportBlocked(new ReportBlockedEvent(UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD — ReportAnalysis.needsReupload()와 동일한 문구로 발송된다(조회 API·푸시 단일 진실)")
  void onReportNeedsReuploadUsesSameCopyAsAnalysisStatus() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.REPORT_NEEDS_REUPLOAD), anyString(), anyString())).willReturn(true);

    // When
    listener.onReportNeedsReupload(new ReportNeedsReuploadEvent(userId, reportId));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq("문서를 다시 올려주세요"),
        eq(ReportAnalysis.needsReupload().failureMessage()), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "REPORT_NEEDS_REUPLOAD")
        .containsEntry("reportId", reportId.toString());
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD — 청구 fan-in으로 문서가 한 건 특정되면 문서명을 담은 문구로 발송된다")
  void onReportNeedsReuploadNamesSingleFailedDocument() {
    // Given: PR #67 — 저널에 흔적이 남는 케이스라 attachment_id로 특정 문서를 좁힐 수 있다.
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID attachmentId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalFailures(List.of(reportId))).willReturn(
        Map.of(reportId, List.of(
            OcrJobFailureViewFixture.terminal(reportId, attachmentId, "unreadable_file", null))));
    ReportAttachment attachment = ReportAttachment.of(reportId, "보험증권.pdf", "https://example.test/doc",
        "application/pdf", "policy");
    ReflectionTestUtils.setField(attachment, "id", attachmentId);
    given(reportAttachmentRepository.findById(attachmentId)).willReturn(Optional.of(attachment));
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.REPORT_NEEDS_REUPLOAD), anyString(), anyString())).willReturn(true);

    // When
    listener.onReportNeedsReupload(new ReportNeedsReuploadEvent(userId, reportId));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq("문서를 다시 올려주세요"),
        eq("업로드하신 [보험증권.pdf] 문서를 알아보기 어려워요. 다시 촬영해서 올려주세요."), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "REPORT_NEEDS_REUPLOAD")
        .containsEntry("reportId", reportId.toString())
        .containsEntry("attachmentId", attachmentId.toString());
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD — 저널 조회가 실패하면 일반 문구로 degrade한다(예외 전파 없음)")
  void onReportNeedsReuploadFallsBackToGenericWhenJournalLookupFails() {
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalFailures(List.of(reportId)))
        .willThrow(new InvalidDataAccessResourceUsageException("GRANT 미적용"));
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.REPORT_NEEDS_REUPLOAD), anyString(), anyString())).willReturn(true);

    assertThatCode(() -> listener.onReportNeedsReupload(new ReportNeedsReuploadEvent(userId, reportId)))
        .doesNotThrowAnyException();

    verify(pushNotificationService).sendToUser(
        eq(userId), eq("문서를 다시 올려주세요"), eq(ReportAnalysis.needsReupload().failureMessage()), any());
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD — 토글이 꺼져 record()가 false면 푸시하지 않는다")
  void onReportNeedsReuploadSkipsPushWhenNotAllowed() {
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(false);

    listener.onReportNeedsReupload(new ReportNeedsReuploadEvent(UUID.randomUUID(), UUID.randomUUID()));

    verify(pushNotificationService, never()).sendToUser(any(), any(), any(), any());
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD — FCM이 실패해도 예외가 전파되지 않는다(스윕 커밋에 영향 없음)")
  void onReportNeedsReuploadSwallowsPushException() {
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);
    willThrow(new RuntimeException("FCM down"))
        .given(pushNotificationService).sendToUser(any(), any(), any(), any());

    assertThatCode(() ->
        listener.onReportNeedsReupload(new ReportNeedsReuploadEvent(UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
  }
}
