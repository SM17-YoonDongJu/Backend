package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * API#1·#2 검수 대기 목록 조회 프로젝션(QueryDSL). issueCount·held는 상관 서브쿼리로 채운다.
 *
 * <p>사건번호는 담지 않는다. 응답 DTO가 {@code reportId}와 {@code createdAt}(리포트 생성 시각)으로 만든다(#413).
 * 그래서 {@code createdAt}은 반드시 리포트({@code reports.created_at})의 값이어야 한다.
 */
public record PendingReviewRow(
    UUID reportId,
    String title,
    AccidentType accidentType,
    List<String> region,
    ReportStatus status,
    Integer claimedMinAmount,
    Integer claimedMaxAmount,
    Integer offeredAmount,
    Long issueCount,
    Boolean held,
    LocalDateTime createdAt) {
}
