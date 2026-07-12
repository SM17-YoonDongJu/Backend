package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/** API#1·#2 검수 대기 목록 조회 프로젝션(QueryDSL). issueCount·held는 상관 서브쿼리로 채운다. */
public record PendingReviewRow(
    UUID reportId,
    String caseNo,
    String title,
    AccidentType accidentType,
    String region,
    ReportStatus status,
    Long claimedMinAmount,
    Long claimedMaxAmount,
    Long offeredAmount,
    Long issueCount,
    Boolean held,
    LocalDateTime createdAt) {
}
