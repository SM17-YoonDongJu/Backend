package com.soma.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API#3 보류 추가 요청 바디(snake_case 수신 → camelCase 필드).
 * reason은 필수(HoldReason enum 이름), reasonDetail은 선택이되 reason이 OTHER면 필수다.
 * 검증은 {@code ReportHoldCommandService}가 수행한다.
 */
public record HoldReportRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
    @Schema(nullable = true, description = "reason이 OTHER면 필수") String reasonDetail) {
}
