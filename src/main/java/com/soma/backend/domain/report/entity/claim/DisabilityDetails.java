package com.soma.backend.domain.report.entity.claim;

import java.util.List;

import com.soma.backend.domain.report.entity.AccidentType;

/** 후유장해(disability) 청구 상세 — 공통 필드만 보유(design.md §3.1, 스펙 확정 시 확장). */
public record DisabilityDetails(
    List<String> diagnosis,
    List<Hospitalization> hospitalizations) implements ClaimDetails {

  @Override
  public AccidentType type() {
    return AccidentType.DISABILITY;
  }
}
