package com.soma.backend.domain.report.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** REPORTS.accident_type 값 객체(팀 ERD 정합). DB·JSON 모두 소문자 문자열 값 사용. */
public enum AccidentType {
  MEDICAL_INDEMNITY("medical_indemnity"),
  TRAFFIC("traffic"),
  DISABILITY("disability"),
  CANCER_DIAGNOSIS("cancer_diagnosis"),
  FIRE("fire"),
  LIABILITY("liability"),
  OTHER("other");

  private final String value;

  AccidentType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static AccidentType from(String value) {
    for (AccidentType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown accident_type: " + value);
  }
}
