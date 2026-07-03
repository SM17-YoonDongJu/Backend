package com.soma.backend.domain.report.entity;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code List<String>} ↔ text 컬럼 변환기. 구분자로 unit separator(코드 포인트 31)를 사용해
 * 자유 텍스트(보장명 등) 내 콤마와 충돌하지 않도록 한다.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

  private static final String DELIMITER = String.valueOf((char) 31);

  @Override
  public String convertToDatabaseColumn(List<String> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    return String.join(DELIMITER, attribute);
  }

  @Override
  public List<String> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isEmpty()) {
      return List.of();
    }
    return Arrays.stream(dbData.split(DELIMITER)).collect(Collectors.toList());
  }
}
