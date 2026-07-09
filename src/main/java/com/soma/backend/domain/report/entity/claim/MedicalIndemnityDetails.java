package com.soma.backend.domain.report.entity.claim;

import java.util.List;

import com.soma.backend.domain.report.entity.AccidentType;

/**
 * 실손의료비(medical_indemnity) 청구 상세 — 유일하게 확장 필드를 갖는 유형(design.md §3.1).
 *
 * @param nonPaymentStatus 비급여 해당 여부(included/excluded/null)
 */
public record MedicalIndemnityDetails(
    List<String> diagnosis,
    List<Hospitalization> hospitalizations,
    List<String> treatmentTypes,
    List<String> outpatientDates,
    String nonPaymentStatus,
    String surgeryDate,
    List<RiderInfo> riderInfo) implements ClaimDetails {

  @Override
  public AccidentType type() {
    return AccidentType.MEDICAL_INDEMNITY;
  }
}
