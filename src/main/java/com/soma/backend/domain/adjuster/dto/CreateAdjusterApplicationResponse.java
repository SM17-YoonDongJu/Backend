package com.soma.backend.domain.adjuster.dto;

import java.util.UUID;

import com.soma.backend.domain.adjuster.entity.AdjusterApplication;

/** 자격 신청 접수 응답(POST /users/adjuster-applications). */
public record CreateAdjusterApplicationResponse(UUID applicationId, String status) {

  public static CreateAdjusterApplicationResponse from(AdjusterApplication application) {
    return new CreateAdjusterApplicationResponse(application.getId(), application.getStatus().name());
  }
}
