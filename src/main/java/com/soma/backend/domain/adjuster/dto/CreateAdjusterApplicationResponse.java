package com.soma.backend.domain.adjuster.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.adjuster.entity.AdjusterApplication;

/** 자격 신청 접수 응답(POST /users/adjuster-applications). */
public record CreateAdjusterApplicationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID applicationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) {

  public static CreateAdjusterApplicationResponse from(AdjusterApplication application) {
    return new CreateAdjusterApplicationResponse(application.getId(), application.getStatus().name());
  }
}
