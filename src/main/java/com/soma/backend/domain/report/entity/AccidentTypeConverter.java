package com.soma.backend.domain.report.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** AccidentType ↔ DB 소문자 문자열(medical_indemnity 등) 변환. */
@Converter
public class AccidentTypeConverter implements AttributeConverter<AccidentType, String> {

  @Override
  public String convertToDatabaseColumn(AccidentType attribute) {
    return attribute == null ? null : attribute.getValue();
  }

  @Override
  public AccidentType convertToEntityAttribute(String dbData) {
    return dbData == null ? null : AccidentType.from(dbData);
  }
}
