package com.soma.backend.global.common;

import java.util.Arrays;
import java.util.List;

/**
 * 지역(text[])을 프론트 계약에 맞춰 단일 문자열로 합친다. null/빈 값은 "". 복수 지역은 {@code ·}로 연결한다.
 */
public final class RegionFormat {

  private RegionFormat() {
  }

  public static String toSingle(List<String> region) {
    if (region == null || region.isEmpty()) {
      return "";
    }
    return String.join("·", region);
  }

  /**
   * 프론트가 보낸 단일 문자열(단일 지역, 복수면 {@code ·}로 연결)을 저장용 text[] 리스트로 되돌린다.
   * null/빈 문자열은 빈 리스트. {@link #toSingle}의 역변환이다.
   */
  public static List<String> toList(String single) {
    if (single == null || single.isBlank()) {
      return List.of();
    }
    return Arrays.stream(single.split("·"))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }
}
