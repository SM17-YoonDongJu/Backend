package com.soma.backend.domain.report.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.claim.Hospitalization;

/**
 * POST /reports 요청 본문(design.md §6). diagnosis·hospitalizations는 UserClaim.details(jsonb)로 조립되고,
 * 나머지는 공통 컬럼/첨부(documents)로 매핑된다.
 */
public record CreateReportRequest(
    UUID productId,
    AccidentType accidentType,
    LocalDate accidentDate,
    List<String> diagnosis,
    Long offeredAmount,
    List<Hospitalization> hospitalizations,
    String description,
    String additionalInformation,
    List<Document> documents,
    String question) {

  /** 프론트가 S3에 직접 업로드한 뒤 전달하는 문서 메타(design.md §6). */
  public record Document(String s3Url, String name, String reportType, String fileType) {
  }
}
