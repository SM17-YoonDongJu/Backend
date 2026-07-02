package com.soma.backend.report.application.dto;

import java.util.UUID;

/** API#3 보류 토글 결과. */
public record HoldToggleResult(UUID reportId, boolean held) {
}
