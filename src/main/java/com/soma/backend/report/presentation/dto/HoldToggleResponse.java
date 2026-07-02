package com.soma.backend.report.presentation.dto;

import java.util.UUID;

import com.soma.backend.report.application.dto.HoldToggleResult;

/** API#3 응답. */
public record HoldToggleResponse(UUID reportId, boolean held) {

  public static HoldToggleResponse from(HoldToggleResult result) {
    return new HoldToggleResponse(result.reportId(), result.held());
  }
}
