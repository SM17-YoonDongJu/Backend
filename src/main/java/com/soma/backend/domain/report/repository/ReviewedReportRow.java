package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * API#5 내 검수 내역 목록 QueryDSL 생성자 프로젝션. {@code accidentType}·{@code status}는 enum으로 받아 DTO에서
 * 문자열로 변환한다. {@code confirmedMinAmount}·{@code confirmedMaxAmount}는 REPORT_REVIEWS.estimate_min/max_amount
 * (사정사 추산 = 화면 "확정 범위"), {@code rating}은 고객의 사건별 평점(ADJUSTER_REVIEWS.score, 미작성 null)이다.
 */
public record ReviewedReportRow(
    UUID reportId,
    String caseNo,
    String title,
    AccidentType accidentType,
    List<String> region,
    ReviewStatus status,
    LocalDateTime reviewedAt,
    Long confirmedMinAmount,
    Long confirmedMaxAmount,
    Integer rating) {
}
