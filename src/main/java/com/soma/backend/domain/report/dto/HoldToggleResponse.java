package com.soma.backend.domain.report.dto;

import java.util.UUID;

/** API#3 응답. */
public record HoldToggleResponse(UUID reportId, boolean held) {
}
