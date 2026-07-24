package com.soma.backend.domain.report.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/** AccidentType ↔ DB 소문자 문자열(medical_indemnity 등) 변환. */
@Slf4j
@Converter
public class AccidentTypeConverter implements AttributeConverter<AccidentType, String> {

  @Override
  public String convertToDatabaseColumn(AccidentType attribute) {
    return attribute == null ? null : attribute.getValue();
  }

  /**
   * DB 값 → enum. 알 수 없는 값(구 한글 라벨·오타 등)은 예외 대신 {@link AccidentType#OTHER}로 관대하게
   * 매핑하고 경고 로그만 남긴다 — 한 행의 잘못된 accident_type이 목록 전체 조회를 500으로 터뜨리지 않게 한다.
   */
  @Override
  public AccidentType convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    try {
      return AccidentType.from(dbData);
    } catch (IllegalArgumentException ex) {
      log.warn("unknown accident_type: {}", dbData);
      return AccidentType.OTHER;
    }
  }
}
