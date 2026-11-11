package com.soma.backend.domain.report.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.claim.Hospitalization;

/**
 * POST /reports 요청 본문(design.md §6). diagnosis·hospitalizations는 UserClaim.details(PII 암호화, 이슈 #235)로
 * 조립되고, 나머지는 공통 컬럼/첨부(documents)로 매핑된다.
 */
public record CreateReportRequest(
    @Schema(nullable = true) UUID productId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccidentType accidentType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate accidentDate,
    @Schema(nullable = true) List<String> diagnosis,
    @Schema(nullable = true) Integer offeredAmount,
    @Schema(nullable = true) List<Hospitalization> hospitalizations,
    @Schema(nullable = true) String description,
    @Schema(nullable = true) String additionalInformation,
    @Schema(nullable = true) List<Document> documents,
    @Schema(nullable = true) String question) {

  /** 프론트가 S3에 직접 업로드한 뒤 전달하는 문서 메타(design.md §6). */
  public record Document(
      @Schema(nullable = true) String s3Url,
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String reportType,
      @Schema(nullable = true) String fileType) {
  }
}
