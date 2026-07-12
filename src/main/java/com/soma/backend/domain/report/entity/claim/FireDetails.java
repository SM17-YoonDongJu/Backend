package com.soma.backend.domain.report.entity.claim;

import java.util.List;

import com.soma.backend.domain.report.entity.AccidentType;

/** 화재(fire) 청구 상세 — 공통 필드만 보유(design.md §3.1, 스펙 확정 시 확장). */
public record FireDetails(
    List<String> diagnosis,
    List<Hospitalization> hospitalizations) implements ClaimDetails {

  @Override
  public AccidentType type() {
    return AccidentType.FIRE;
  }
}
