package com.soma.backend.domain.report.repository;

import java.util.UUID;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** pending-review 목록 항목별 요청 사정사 본인 검수 상태 조회용 JPQL 생성자 프로젝션. */
public record ReportReviewStatusRow(UUID reportId, ReviewStatus status) {
}
