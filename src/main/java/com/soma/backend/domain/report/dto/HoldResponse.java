package com.soma.backend.domain.report.dto;

import java.util.UUID;

/** API#3 보류 추가 응답. held=true. */
public record HoldResponse(UUID reportId, boolean held) {
}
