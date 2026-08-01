package com.soma.backend.domain.report.dto;

import java.util.UUID;

import com.soma.backend.domain.report.entity.ReportStatus;

/** POST /reports 202 응답(design.md §6). */
public record CreateReportResponse(UUID reportId, ReportStatus status) {
}
