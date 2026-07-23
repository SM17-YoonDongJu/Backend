package com.soma.backend.domain.user.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/** 대시보드 대표 활성 리포트 기본 필드 projection(최신 활성 1건). first_reviewed_at·proposal_count는 별도 조회. */
public record ActiveReportRow(
    UUID reportId,
    String title,
    AccidentType accidentType,
    ReportStatus status,
    LocalDateTime createdAt) {
}
