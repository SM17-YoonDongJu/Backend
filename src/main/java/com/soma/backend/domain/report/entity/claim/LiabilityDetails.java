package com.soma.backend.domain.report.entity.claim;

import java.util.List;

import com.soma.backend.domain.report.entity.AccidentType;

/** 배상책임(liability) 청구 상세 — 공통 필드만 보유(design.md §3.1, 스펙 확정 시 확장). */
public record LiabilityDetails(
    List<String> diagnosis,
    List<Hospitalization> hospitalizations) implements ClaimDetails {

  @Override
  public AccidentType type() {
    return AccidentType.LIABILITY;
  }
}
