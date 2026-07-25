package com.soma.backend.global.common;

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
}
