package com.soma.backend.domain.report.repository;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** API#5 필터 탭 건수 배지용 상태별 건수(REPORT_REVIEWS.status 기준) JPQL 생성자 프로젝션. */
public record StatusCount(ReviewStatus status, Long count) {
}
