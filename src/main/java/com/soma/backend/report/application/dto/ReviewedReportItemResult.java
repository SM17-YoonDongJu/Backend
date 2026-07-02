package com.soma.backend.report.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** API#5 내 검수 내역 목록 아이템 결과. */
public record ReviewedReportItemResult(
    UUID reportId,
    String caseNo,
    String title,
    String accidentType,
    String region,
    String outcome,
    LocalDateTime reviewedAt) {
}
