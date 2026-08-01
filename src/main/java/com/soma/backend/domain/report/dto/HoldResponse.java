package com.soma.backend.domain.report.dto;

import java.util.UUID;

/** API#3 보류 추가 응답. held=true, 반영된 사유(reason)와 상세 텍스트(reasonDetail)를 에코한다. */
public record HoldResponse(UUID reportId, boolean held, String reason, String reasonDetail) {
}
