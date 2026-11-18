package com.soma.backend.domain.report.repository;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** API#5 필터 탭 건수 배지용 상태별 건수(REPORT_REVIEWS.status 기준) QueryDSL 생성자 프로젝션({@code Projections.constructor}). */
public record StatusCount(ReviewStatus status, Long count) {
}
