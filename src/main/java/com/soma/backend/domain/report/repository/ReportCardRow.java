package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * GET /reports 고객 리포트 목록 projection(QueryDSL, per-review). report_reviews 1건당 1행이며,
 * reviewedAt·adjusterNickname은 그 리뷰(report_reviews) 값, 나머지(status·accidentType·claimed·offered·
 * treatment·proposalCount)는 소속 리포트(reports) 값이다. proposalCount는 REJECTED 제외 리뷰 수(리포트 단위),
 * status는 REPORTS.status(응답에서 CLOSED→MATCHED 매핑), adjusterNickname은 리뷰 담당 사정사가 없으면 null.
 */
public record ReportCardRow(
    UUID reportId,
    ReportStatus status,
    AccidentType accidentType,
    String title,
    LocalDateTime createdAt,
    String caseNo,
    Long claimedMinAmount,
    Long claimedMaxAmount,
    Long proposalCount,
    LocalDateTime reviewedAt,
    String adjusterNickname,
    Long offeredAmount,
    String treatment) {
}
