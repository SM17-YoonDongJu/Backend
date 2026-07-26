package com.soma.backend.domain.report.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * GET /reports 고객 대시보드 카드 목록 프로젝션(QueryDSL, design.md §6). native 대신 QueryDSL
 * record projection으로 받는다(하네스 규칙). 채택된 제안(report_reviews.status=ACCEPTED)을 LEFT JOIN해
 * 확정 사정사·확정 견적·평점을 붙이고, proposalCount는 REJECTED 제외 상관 서브쿼리로 채운다.
 * ACCEPTED 제안이 없으면 reviewedAt·adjusterNickname·confirmedMin/Max·rating은 null이다.
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
    Long confirmedMinAmount,
    Long confirmedMaxAmount,
    BigDecimal rating) {
}
