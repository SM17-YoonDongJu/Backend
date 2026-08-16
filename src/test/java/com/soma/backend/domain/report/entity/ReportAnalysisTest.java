package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ReportAnalysis} 판정 VO 단위 테스트(design.md §15 Q1~Q7).
 *
 * <p>판정 우선순위(성공 &gt; 확정 실패 &gt; 처리 중)는 서비스의 if 체인이 아니라 이 VO가 소유한 불변식이라,
 * 서비스·DB 없이 여기서 직접 고정한다.
 */
@DisplayName("ReportAnalysis 판정 VO 단위 테스트")
class ReportAnalysisTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);

  private static Report reportWithoutDraft() {
    return BeanUtils.instantiateClass(Report.class);
  }

  private static Report reportWithAiDraft() {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));
    return report;
  }

  private static Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  @Test
  @DisplayName("Q1 — 확정 실패 행이 없으면 PROCESSING이고 실패 필드는 전부 비어 있다")
  void noTerminalFailureIsProcessing() {
    // Given: terminal=false 행만 있는 리포트 → 리포지토리가 애초에 걸러서 빈 목록이 온다
    Report report = reportWithoutDraft();

    // When
    ReportAnalysis analysis = ReportAnalysis.of(report, List.of());

    // Then
    assertThat(analysis.state()).isEqualTo(AnalysisState.PROCESSING);
    assertThat(analysis.isFailed()).isFalse();
    assertThat(analysis.reason()).isNull();
    assertThat(analysis.failedAt()).isNull();
    assertThat(analysis.failureMessage()).isNull();
    assertThat(analysis.reuploadGuidance()).isNull();
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("Q2 — 확정 실패 1건이면 FAILED + 사유·문구·재업로드 안내·실패 시각을 채운다")
  void singleTerminalFailureIsFailed() {
    // Given
    Report report = reportWithoutDraft();
    UUID reportId = UUID.randomUUID();
    UUID attachmentId = UUID.randomUUID();
    OcrJobFailureView failure =
        OcrJobFailureViewFixture.terminal(reportId, attachmentId, "unreadable_file", FAILED_AT);

    // When
    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(failure));

    // Then
    assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
    assertThat(analysis.isFailed()).isTrue();
    assertThat(analysis.reason()).isEqualTo(AnalysisFailureReason.UNREADABLE_FILE);
    assertThat(analysis.failureMessage()).isEqualTo("파일을 읽을 수 없어요. 다시 업로드해주세요");
    assertThat(analysis.reuploadGuidance()).isEqualTo(ReuploadGuidance.RECOMMENDED);
    assertThat(analysis.failedAt()).isEqualTo(FAILED_AT);
    assertThat(analysis.documents()).singleElement()
        .satisfies(document -> {
          assertThat(document.attachmentId()).isEqualTo(attachmentId);
          assertThat(document.reason()).isEqualTo(AnalysisFailureReason.UNREADABLE_FILE);
        });
  }

  @Test
  @DisplayName("Q3 — AI 초안이 있으면 확정 실패 행이 남아 있어도 COMPLETED다(성공이 실패를 이긴다)")
  void aiDraftBeatsLingeringFailure() {
    // Given: AI 워커의 실패 행 삭제(clear_job_failure)가 실패해 행이 남은 상황(§8 E3)
    Report report = reportWithAiDraft();
    OcrJobFailureView lingering =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "ocr_error", FAILED_AT);

    // When
    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(lingering));

    // Then
    assertThat(analysis.state()).isEqualTo(AnalysisState.COMPLETED);
    assertThat(analysis.reason()).isNull();
    assertThat(analysis.failedAt()).isNull();
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("F1 후속 — report.status가 BLOCKED면 저널과 무관하게 BLOCKED로 판정하고 문구·재업로드 안내를 채운다")
  void blockedStatusWinsOverJournal() {
    Report report = reportWithStatus(ReportStatus.BLOCKED);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of());

    assertThat(analysis.state()).isEqualTo(AnalysisState.BLOCKED);
    assertThat(analysis.isFailed()).isFalse();
    assertThat(analysis.reason()).isNull();
    assertThat(analysis.failedAt()).isNull();
    assertThat(analysis.failureMessage()).isEqualTo("처리할 수 없는 요청이에요. 고객센터로 문의해주세요.");
    assertThat(analysis.reuploadGuidance()).isEqualTo(ReuploadGuidance.NOT_SUPPORTED);
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("F1 후속 — BLOCKED는 확정 실패 저널 행이 남아 있어도 우선한다(가드레일은 OCR 이전에 끊기므로 실제로는 안 섞이지만 방어적으로 고정)")
  void blockedBeatsLingeringTerminalFailure() {
    Report report = reportWithStatus(ReportStatus.BLOCKED);
    OcrJobFailureView lingering =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "ocr_error", FAILED_AT);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(lingering));

    assertThat(analysis.state()).isEqualTo(AnalysisState.BLOCKED);
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("report.status가 NEEDS_REUPLOAD면 저널과 무관하게 NEEDS_REUPLOAD로 판정하고 문구·재업로드 안내를 채운다")
  void needsReuploadStatusIsDerivedFromReportStatus() {
    Report report = reportWithStatus(ReportStatus.NEEDS_REUPLOAD);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of());

    assertThat(analysis.state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
    assertThat(analysis.isFailed()).isFalse();
    assertThat(analysis.reason()).isNull();
    assertThat(analysis.failedAt()).isNull();
    assertThat(analysis.failureMessage()).isEqualTo("업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.");
    assertThat(analysis.reuploadGuidance()).isEqualTo(ReuploadGuidance.RECOMMENDED);
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("핵심 — AI 초안이 있어도 NEEDS_REUPLOAD가 이긴다(COMPLETED로 내리면 무음 정지가 재현된다)")
  void needsReuploadBeatsAiDraft() {
    // Given: AI가 일부 문서로 초안을 만든 뒤 품질 판정을 늦게 쓴 모순 상태
    Report report = reportWithStatus(ReportStatus.NEEDS_REUPLOAD);
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of());

    assertThat(analysis.state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
  }

  @Test
  @DisplayName("핵심 — NEEDS_REUPLOAD는 확정 실패 행이 공존해도 isFailed()가 false다(저널 스윕 중복 알림 차단)")
  void needsReuploadKeepsIsFailedFalseWhenJournalRowsCoexist() {
    // Given: 품질 판정에는 저널 행이 안 생긴다는 가정이 틀린 경우(방어적 고정)
    Report report = reportWithStatus(ReportStatus.NEEDS_REUPLOAD);
    OcrJobFailureView lingering =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "ocr_error", FAILED_AT);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(lingering));

    // Then: AnalysisFailureNotificationSweeper는 isFailed()로 통지 대상을 고르므로 중복 알림이 나가지 않는다.
    assertThat(analysis.state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
    assertThat(analysis.isFailed()).isFalse();
  }

  @Test
  @DisplayName("청구 fan-in으로 걸린 NEEDS_REUPLOAD는 저널에 흔적이 있으면 문서를 특정해 보여준다")
  void needsReuploadFromClaimFanInExposesFailedDocuments() {
    // Given: PR #67 — 필수 문서 미인식·비필수 문서 결정적 실패는 개별 문서 품질 게이트와 달리 저널에 남는다.
    Report report = reportWithStatus(ReportStatus.NEEDS_REUPLOAD);
    UUID attachmentId = UUID.randomUUID();
    OcrJobFailureView fanInFailure =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), attachmentId, "unreadable_file", FAILED_AT);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(fanInFailure));

    // Then: 상태·문구·isFailed()는 그대로지만(모순 없음), 문서 목록은 저널에서 채워진다.
    assertThat(analysis.state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
    assertThat(analysis.isFailed()).isFalse();
    assertThat(analysis.reason()).isNull();
    assertThat(analysis.failedAt()).isNull();
    assertThat(analysis.documents()).hasSize(1);
    assertThat(analysis.documents().getFirst().attachmentId()).isEqualTo(attachmentId);
    assertThat(analysis.documents().getFirst().reason()).isEqualTo(AnalysisFailureReason.UNREADABLE_FILE);
  }

  @Test
  @DisplayName("개별 문서 품질 게이트로 걸린 NEEDS_REUPLOAD는 저널에 흔적이 없어 문서 목록이 빈 배열이다")
  void needsReuploadFromQualityGateHasEmptyDocuments() {
    Report report = reportWithStatus(ReportStatus.NEEDS_REUPLOAD);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of());

    assertThat(analysis.state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
    assertThat(analysis.documents()).isEmpty();
  }

  @Test
  @DisplayName("Q4 — 실패 행이 삭제되면(정상 회복) 같은 리포트가 PROCESSING으로 되돌아간다")
  void recoveryReturnsToProcessing() {
    // Given
    Report report = reportWithoutDraft();
    List<OcrJobFailureView> failures = new ArrayList<>();
    failures.add(OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "ocr_error", FAILED_AT));
    assertThat(ReportAnalysis.of(report, failures).state()).isEqualTo(AnalysisState.FAILED);

    // When: AI 워커가 회복해 저널 행을 지웠다(상태를 저장하지 않기에 가능한 전이 — §8 E2)
    failures.clear();

    // Then
    assertThat(ReportAnalysis.of(report, failures).state()).isEqualTo(AnalysisState.PROCESSING);
  }

  @Test
  @DisplayName("Q5(최중요) — masking_residual + unreadable_file 혼재 시 대표는 MASKING_RESIDUAL·안내는 NOT_SUPPORTED")
  void mixedReasonsNeverAdviseReupload() {
    // Given: 문서 2건이 서로 다른 사유로 실패
    Report report = reportWithoutDraft();
    UUID reportId = UUID.randomUUID();
    UUID maskedDocument = UUID.randomUUID();
    UUID brokenDocument = UUID.randomUUID();
    List<OcrJobFailureView> failures = List.of(
        OcrJobFailureViewFixture.terminal(reportId, brokenDocument, "unreadable_file", FAILED_AT.plusMinutes(5)),
        OcrJobFailureViewFixture.terminal(reportId, maskedDocument, "masking_residual", FAILED_AT));

    // When
    ReportAnalysis analysis = ReportAnalysis.of(report, failures);

    // Then: 리포트 대표는 재업로드로 해결되지 않는 사유이고, 문서별 사유는 그대로 보존된다
    assertThat(analysis.reason()).isEqualTo(AnalysisFailureReason.MASKING_RESIDUAL);
    assertThat(analysis.reuploadGuidance()).isEqualTo(ReuploadGuidance.NOT_SUPPORTED);
    assertThat(analysis.failureMessage()).isEqualTo("문서를 검토 중입니다");
    assertThat(analysis.documents())
        .extracting(ReportAnalysis.FailedDocument::reason)
        .containsExactlyInAnyOrder(AnalysisFailureReason.UNREADABLE_FILE, AnalysisFailureReason.MASKING_RESIDUAL);
    // 대표 실패 시각은 가장 이른 first_failed_at이다.
    assertThat(analysis.failedAt()).isEqualTo(FAILED_AT);
  }

  @Test
  @DisplayName("Q7 — attachment_id가 NULL인 실패 행도 예외 없이 문서 목록에 들어간다(식별자만 null)")
  void nullAttachmentIdIsTolerated() {
    // Given: poison 훅·역직렬화 실패처럼 문서 식별자가 없는 실패(§8 E9)
    Report report = reportWithoutDraft();
    OcrJobFailureView failure =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), null, "schema_invalid", FAILED_AT);

    // When & Then
    assertThatCode(() -> ReportAnalysis.of(report, List.of(failure))).doesNotThrowAnyException();
    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(failure));
    assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
    assertThat(analysis.documents()).singleElement()
        .satisfies(document -> {
          assertThat(document.attachmentId()).isNull();
          assertThat(document.reason()).isEqualTo(AnalysisFailureReason.SCHEMA_INVALID);
        });
  }

  @Test
  @DisplayName("Q6 — 미지의 failure_class 행도 UNKNOWN 사유로 판정되고 예외가 나지 않는다")
  void unknownFailureClassRowFallsBack() {
    Report report = reportWithoutDraft();
    OcrJobFailureView failure =
        OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "brand_new_value", FAILED_AT);

    ReportAnalysis analysis = ReportAnalysis.of(report, List.of(failure));

    assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
    assertThat(analysis.reason()).isEqualTo(AnalysisFailureReason.UNKNOWN);
    assertThat(analysis.reuploadGuidance()).isEqualTo(ReuploadGuidance.HOLD);
  }

  @Test
  @DisplayName("first_failed_at이 NULL인 행이 섞여도 대표 실패 시각은 남은 값의 최솟값이다")
  void failedAtIgnoresNullTimestamps() {
    Report report = reportWithoutDraft();
    UUID reportId = UUID.randomUUID();
    List<OcrJobFailureView> failures = List.of(
        OcrJobFailureViewFixture.terminal(reportId, UUID.randomUUID(), "ocr_error", null),
        OcrJobFailureViewFixture.terminal(reportId, UUID.randomUUID(), "ocr_error", FAILED_AT.plusHours(1)),
        OcrJobFailureViewFixture.terminal(reportId, UUID.randomUUID(), "ocr_error", FAILED_AT));

    ReportAnalysis analysis = ReportAnalysis.of(report, failures);

    assertThat(analysis.failedAt()).isEqualTo(FAILED_AT);
  }

  @Test
  @DisplayName("documents는 방어 복사된 불변 리스트다(호출자가 판정 결과를 바꿀 수 없다)")
  void documentsAreImmutableCopy() {
    Report report = reportWithoutDraft();
    List<OcrJobFailureView> failures = new ArrayList<>();
    failures.add(OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "ocr_error", FAILED_AT));

    ReportAnalysis analysis = ReportAnalysis.of(report, failures);
    failures.clear();

    assertThat(analysis.documents()).hasSize(1);
    assertThatThrownBy(() -> analysis.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("정적 팩터리(processing·completed·blocked·needsReupload)는 실패 필드가 비고 documents는 빈 리스트다")
  void staticFactoriesAreEmptyAndNonNull() {
    assertThat(ReportAnalysis.processing().state()).isEqualTo(AnalysisState.PROCESSING);
    assertThat(ReportAnalysis.processing().documents()).isNotNull().isEmpty();
    assertThat(ReportAnalysis.completed().state()).isEqualTo(AnalysisState.COMPLETED);
    assertThat(ReportAnalysis.completed().documents()).isNotNull().isEmpty();
    assertThat(ReportAnalysis.blocked().state()).isEqualTo(AnalysisState.BLOCKED);
    assertThat(ReportAnalysis.blocked().documents()).isNotNull().isEmpty();
    assertThat(ReportAnalysis.needsReupload().state()).isEqualTo(AnalysisState.NEEDS_REUPLOAD);
    assertThat(ReportAnalysis.needsReupload().documents()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("documents에 null을 넘겨도 빈 리스트로 정규화된다(응답 계약: null 아님)")
  void nullDocumentsNormalizedToEmptyList() {
    ReportAnalysis analysis = new ReportAnalysis(AnalysisState.PROCESSING, null, null, null);

    assertThat(analysis.documents()).isNotNull().isEmpty();
  }
}
