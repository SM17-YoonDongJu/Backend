package com.soma.backend.domain.report.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.entity.OcrJobFailureViewFixture;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReuploadCause;

/**
 * 분석 사유 코드 응답 매핑 단위 테스트(design.md §9-1 U6) — {@link ReportResponseSupport#analysisFailureReason}가 목록
 * 카드·상세·폴링 세 응답의 단일 매핑 지점이다. FAILED면 저널 대표 사유, NEEDS_REUPLOAD면 확인된 재업로드 사유를 같은
 * 필드로 내보내고, 그 밖은 null이다.
 */
@DisplayName("분석 사유 코드 응답 매핑 단위 테스트")
class AnalysisFailureReasonMappingTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 11, 14, 9, 0, 0);
  private static final String GENERIC_REUPLOAD_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";

  private static Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  private static ReportAnalysis needsReupload(ReuploadCause cause) {
    return ReportAnalysis.of(reportWithStatus(ReportStatus.NEEDS_REUPLOAD), List.of(), List.of(), List.of(), cause);
  }

  @Test
  @DisplayName("FAILED는 저널 대표 사유(AnalysisFailureReason) 이름이다 — 기존 동작 그대로")
  void failedMapsJournalReason() {
    ReportAnalysis failed = ReportAnalysis.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION),
        List.of(OcrJobFailureViewFixture.terminal(UUID.randomUUID(), UUID.randomUUID(), "unreadable_file", FAILED_AT)),
        List.of());

    assertThat(ReportResponseSupport.analysisFailureReason(failed)).isEqualTo("UNREADABLE_FILE");
  }

  static Stream<Arguments> reuploadCauses() {
    return Stream.of(
        Arguments.of("off_domain", List.of(), "DOMAIN_MISMATCH"),
        Arguments.of("missing_required", List.of("policy"), "MISSING_REQUIRED_DOCUMENTS"),
        Arguments.of("missing_required", List.of(), "MISSING_REQUIRED_DOCUMENTS"));
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @MethodSource("reuploadCauses")
  @DisplayName("NEEDS_REUPLOAD + 재업로드 사유는 그 사유 코드다(ReuploadReason 상수 이름)")
  void needsReuploadMapsReuploadReason(String reupload, List<String> missingDocTypes, String expected) {
    ReportAnalysis analysis = needsReupload(ReuploadCause.from(reupload, missingDocTypes));

    assertThat(ReportResponseSupport.analysisFailureReason(analysis)).isEqualTo(expected);
  }

  static Stream<Arguments> analysesWithoutReason() {
    return Stream.of(
        Arguments.of("NEEDS_REUPLOAD(사유 미확인)", needsReupload(null)),
        Arguments.of("BLOCKED", ReportAnalysis.blocked()),
        Arguments.of("PROCESSING", ReportAnalysis.processing()),
        Arguments.of("COMPLETED", ReportAnalysis.completed()),
        Arguments.of("null(조회 degrade)", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("analysesWithoutReason")
  @DisplayName("사유를 확인하지 못한 NEEDS_REUPLOAD·BLOCKED·PROCESSING·COMPLETED·null은 null이다")
  void otherAnalysesMapToNull(String label, ReportAnalysis analysis) {
    assertThat(ReportResponseSupport.analysisFailureReason(analysis)).isNull();
  }

  @Test
  @DisplayName("폴링 응답(ReportAnalysisStatusResponse.of)도 같은 매핑 지점으로 사유·문구를 싣는다")
  void pollingResponseUsesSameMapping() {
    ReportAnalysis analysis = needsReupload(ReuploadCause.from("missing_required", List.of("diagnosis")));

    ReportAnalysisStatusResponse response = ReportAnalysisStatusResponse.of(UUID.randomUUID(), analysis, Map.of());

    assertThat(response.analysisState()).isEqualTo("NEEDS_REUPLOAD");
    assertThat(response.failureReason()).isEqualTo("MISSING_REQUIRED_DOCUMENTS")
        .isEqualTo(ReportResponseSupport.analysisFailureReason(analysis));
    assertThat(response.failureMessage())
        .isEqualTo("업로드하신 문서에서 진단서를 확인하지 못했어요. 진단서를 다시 올려주세요.")
        .isEqualTo(ReportResponseSupport.analysisFailureMessage(analysis));
    assertThat(response.reuploadGuidance()).isEqualTo("RECOMMENDED");
    assertThat(response.failedAt()).isNull();
    assertThat(response.failedDocuments()).isEmpty();
  }

  @Test
  @DisplayName("사유를 확인하지 못한 NEEDS_REUPLOAD 폴링 응답은 지금과 같다(사유 null·일반 문구)")
  void pollingResponseWithoutCauseIsUnchanged() {
    ReportAnalysisStatusResponse response =
        ReportAnalysisStatusResponse.of(UUID.randomUUID(), needsReupload(null), Map.of());

    assertThat(response.failureReason()).isNull();
    assertThat(response.failureMessage()).isEqualTo(GENERIC_REUPLOAD_MESSAGE);
    assertThat(response.reuploadGuidance()).isEqualTo("RECOMMENDED");
  }
}
