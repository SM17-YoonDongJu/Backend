package com.soma.backend.domain.report.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /reports/{reportId}/proposals 목록 QueryDSL 생성자 프로젝션(design.md §6 ProposalListResponse.Proposal).
 * {@code status}는 enum으로 받아 DTO에서 문자열로 변환한다. {@code rating}은 사정사 프로필의 비정규화 평점
 * (ADJUSTER_PROFILES.rating_mean, scale 2)이며 프로필 행이 없거나 평가가 없으면 null이다.
 */
public record ProposalRow(
    UUID proposalId,
    UUID adjusterId,
    String nickname,
    BigDecimal rating,
    String proposalSummary,
    ReviewStatus status,
    LocalDateTime submittedAt) {
}
