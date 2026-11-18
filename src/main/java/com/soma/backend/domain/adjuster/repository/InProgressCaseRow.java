package com.soma.backend.domain.adjuster.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * 홈 대시보드 "진행 중인 사건" 조회 프로젝션(QueryDSL).
 * report / report_review 크로스-애그리거트 읽기 모델이라 report 엔티티 값 객체(enum)를 참조한다.
 *
 * <p>{@code reportCreatedAt}은 리포트 생성 시각({@code reports.created_at})이다. 응답의 사건번호를 {@code reportId}와
 * 함께 만드는 데만 쓴다(#413). 검수 시각({@code report_reviews.created_at})이 아니다.
 */
public record InProgressCaseRow(
    UUID reportId,
    LocalDateTime reportCreatedAt,
    AccidentType accidentType,
    String title,
    ReportStatus reportStatus,
    ReviewStatus reviewStatus) {
}
