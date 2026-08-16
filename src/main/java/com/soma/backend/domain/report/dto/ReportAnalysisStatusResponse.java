package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.ReportAnalysis;

/**
 * GET /reports/{reportId}/analysis-status — 리포트 분석(OCR·AI) 처리 상태 응답.
 *
 * <p>POST /reports가 202로 끝나므로 FE는 결과를 폴링해야 하는데, 상세 조회(GET /reports/{id})는 크로스-애그리거트
 * 조회 여러 개가 매번 도는 무거운 API라 폴링 대상으로 부적절하다. 분석 상태만 내려주는 얇은 엔드포인트를 따로 둔다
 * (design.md §5-2).
 *
 * <p>조건부 null 필드({@code failure_*}·{@code reupload_guidance}·{@code failed_at})는 중첩 객체가 아니라
 * <b>평면 필드</b>로 설계했다 — swagger-core가 {@code $ref} 필드의 nullable을 의미상 모순인 형태로 렌더링하는
 * 한계를 피하기 위함이다(CLAUDE.md OpenAPI 규칙). 조건은 각 필드 {@code description}에 서술한다.
 *
 * <p>내부 식별자({@code s3_key}·{@code user_ref}·{@code job_id}·{@code message_id}·{@code attempts})와
 * 예외 클래스명({@code error_type})은 이 응답에 절대 포함하지 않는다(design.md §12 S2).
 */
public record ReportAnalysisStatusResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "분석 처리 상태. PROCESSING | COMPLETED | FAILED | BLOCKED | NEEDS_REUPLOAD"
            + "(BLOCKED는 AI 입력 가드레일 차단 — OCR·AI 파이프라인이 시작되지 않는다. "
            + "NEEDS_REUPLOAD는 OCR 품질 미달 — 문서를 다시 올려야 진행된다)",
        allowableValues = {"PROCESSING", "COMPLETED", "FAILED", "BLOCKED", "NEEDS_REUPLOAD"})
    String analysisState,

    @Schema(nullable = true,
        description = "대표 실패 사유. analysis_state가 FAILED일 때만 non-null"
            + "(BLOCKED·NEEDS_REUPLOAD는 저널 기반 사유가 없어 null). "
            + "MASKING_RESIDUAL | SCHEMA_INVALID | OCR_ERROR | UNKNOWN | UNREADABLE_FILE")
    String failureReason,

    @Schema(nullable = true,
        description = "사용자 노출 문구. analysis_state가 FAILED·BLOCKED·NEEDS_REUPLOAD일 때만 non-null")
    String failureMessage,

    @Schema(nullable = true,
        description = "재업로드 안내. analysis_state가 FAILED·BLOCKED·NEEDS_REUPLOAD일 때만 non-null"
            + "(BLOCKED은 항상 NOT_SUPPORTED — 문서 문제가 아니라 재업로드가 무의미하다. "
            + "NEEDS_REUPLOAD는 항상 RECOMMENDED — 재업로드가 유일한 해결책이다). "
            + "RECOMMENDED(재업로드로 해결 가능) | NOT_SUPPORTED(재업로드해도 동일) | HOLD(확인 중)")
    String reuploadGuidance,

    @Schema(nullable = true, description = "대표 실패 시각(최초 실패 기준). analysis_state가 FAILED일 때만 non-null. "
        + "BLOCKED·NEEDS_REUPLOAD는 저널 기반 시각이 없어 null")
    LocalDateTime failedAt,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "실패 문서 목록. FAILED가 아니면 빈 배열(null 아님). NEEDS_REUPLOAD는 현재 빈 배열이며 "
            + "문서 단위 상세는 후속 과제다")
    List<FailedDocument> failedDocuments) {

  /**
   * 판정 결과 VO를 응답으로 옮긴다.
   *
   * @param attachmentNames {@code report_attachments.id → name} 맵. 첨부 행을 찾지 못하면 name은 null이다
   */
  public static ReportAnalysisStatusResponse of(
      UUID reportId, ReportAnalysis analysis, Map<UUID, String> attachmentNames) {
    List<FailedDocument> documents = analysis.documents().stream()
        .map(document -> new FailedDocument(
            document.attachmentId(),
            document.attachmentId() == null ? null : attachmentNames.get(document.attachmentId()),
            document.reason().name()))
        .toList();
    return new ReportAnalysisStatusResponse(
        reportId,
        ReportResponseSupport.analysisState(analysis),
        ReportResponseSupport.analysisFailureReason(analysis),
        ReportResponseSupport.analysisFailureMessage(analysis),
        ReportResponseSupport.reuploadGuidance(analysis),
        analysis.failedAt(),
        documents);
  }

  /**
   * 실패한 문서 1건. 저널에 {@code attachment_id}가 없는 실패(역직렬화 실패·poison 훅 등)도 있어
   * 식별 필드가 전부 nullable이다(design.md §8 E9).
   */
  public record FailedDocument(
      @Schema(nullable = true, description = "저널에 attachment_id가 없으면 null") UUID attachmentId,
      @Schema(nullable = true, description = "첨부 행을 찾지 못하면 null") String name,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "문서별 실패 사유") String failureReason) {
  }
}
