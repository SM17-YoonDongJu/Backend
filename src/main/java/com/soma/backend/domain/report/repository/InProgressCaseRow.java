package com.soma.backend.domain.report.repository;

import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** 홈 대시보드 "진행 중인 사건" 조회 프로젝션(QueryDSL). */
public record InProgressCaseRow(
    UUID reportId,
    String caseNo,
    AccidentType accidentType,
    String title,
    ReportStatus reportStatus,
    ReviewStatus reviewStatus) {
}
