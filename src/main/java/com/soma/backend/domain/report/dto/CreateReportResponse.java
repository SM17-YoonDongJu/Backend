package com.soma.backend.domain.report.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.Report;

/**
 * POST /reports 202 응답(design.md §6). {@code status}는 목록·상세와 같은 고객 노출 매핑
 * ({@link ReportResponseSupport#customerStatus}, {@code CLOSED}→{@code "MATCHED"})을 거친 문자열이다 —
 * 생성 직후는 항상 {@code AWAITING_INSPECTION}이라 지금 당장 값이 달라지진 않지만, raw enum을 그대로
 * 내리면 이 응답만 다른 계약을 갖게 돼 정정했다.
 */
public record CreateReportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) {

  public static CreateReportResponse from(Report report) {
    return new CreateReportResponse(report.getId(), ReportResponseSupport.customerStatus(report.getStatus()));
  }
}
