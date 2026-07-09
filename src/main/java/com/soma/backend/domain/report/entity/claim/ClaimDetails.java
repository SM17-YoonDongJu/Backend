package com.soma.backend.domain.report.entity.claim;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.soma.backend.domain.report.entity.AccidentType;

/**
 * USER_CLAIMS.details(jsonb) 값 객체 — 사고 유형(AccidentType)별 청구 상세 정보(design.md §3.1).
 * {@code type} 프로퍼티로 다형성을 식별하며, {@link #type()}은 각 구현체가 자기 {@link AccidentType}
 * 상수를 반환한다(UserClaim.accidentType과 일치해야 하는 불변식은 {@code UserClaim.create}가 검증).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MedicalIndemnityDetails.class, name = "medical_indemnity"),
    @JsonSubTypes.Type(value = TrafficDetails.class, name = "traffic"),
    @JsonSubTypes.Type(value = DisabilityDetails.class, name = "disability"),
    @JsonSubTypes.Type(value = CancerDiagnosisDetails.class, name = "cancer_diagnosis"),
    @JsonSubTypes.Type(value = FireDetails.class, name = "fire"),
    @JsonSubTypes.Type(value = LiabilityDetails.class, name = "liability"),
    @JsonSubTypes.Type(value = OtherDetails.class, name = "other")
})
public sealed interface ClaimDetails
    permits MedicalIndemnityDetails, TrafficDetails, DisabilityDetails,
            CancerDiagnosisDetails, FireDetails, LiabilityDetails, OtherDetails {

  AccidentType type();

  List<String> diagnosis();

  List<Hospitalization> hospitalizations();

  /**
   * 요청 공통 필드(diagnosis·hospitalizations)로 사고유형별 상세 VO를 조립한다(design.md §6).
   * 실손 확장 필드(treatmentTypes 등)는 현재 요청 계약에 없어 null로 둔다(추후 확장).
   */
  static ClaimDetails of(AccidentType type, List<String> diagnosis, List<Hospitalization> hospitalizations) {
    return switch (type) {
      case MEDICAL_INDEMNITY ->
          new MedicalIndemnityDetails(diagnosis, hospitalizations, null, null, null, null, null);
      case TRAFFIC -> new TrafficDetails(diagnosis, hospitalizations);
      case DISABILITY -> new DisabilityDetails(diagnosis, hospitalizations);
      case CANCER_DIAGNOSIS -> new CancerDiagnosisDetails(diagnosis, hospitalizations);
      case FIRE -> new FireDetails(diagnosis, hospitalizations);
      case LIABILITY -> new LiabilityDetails(diagnosis, hospitalizations);
      case OTHER -> new OtherDetails(diagnosis, hospitalizations);
    };
  }
}
