package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** API#5 내 검수 내역 목록 조회 프로젝션(QueryDSL). */
public record ReviewedReportRow(
    UUID reportId,
    String caseNo,
    String title,
    AccidentType accidentType,
    String region,
    ReviewStatus status,
    LocalDateTime reviewedAt) {
}
