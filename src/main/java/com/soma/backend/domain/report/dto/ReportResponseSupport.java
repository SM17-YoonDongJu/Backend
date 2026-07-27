package com.soma.backend.domain.report.dto;

import java.util.List;

import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * report 응답 DTO 공통 포맷 헬퍼. 프론트 계약 정합용 — 지역(text[])을 단일 문자열로 조인하고,
 * 리스트 응답 필드는 null 대신 빈 배열로 내린다.
 */
final class ReportResponseSupport {

  private ReportResponseSupport() {
  }

  /**
   * 고객 노출용 REPORTS.status 매핑. CLOSED는 FE 계약상 "MATCHED"로 내리고, 나머지는 enum 이름을 그대로 쓴다.
   * null이면 null. 목록 카드({@code ReportCardListResponse.Card})·상세({@code CustomerReportDetailResponse})
   * 양쪽에서 공유하는 단일 매핑 지점이다.
   */
  static String customerStatus(ReportStatus status) {
    if (status == null) {
      return null;
    }
    return status == ReportStatus.CLOSED ? "MATCHED" : status.name();
  }

  /** 문자열 리스트를 구분자로 합친다. null/빈 값은 "". */
  static String joinText(List<String> values, String separator) {
    if (values == null || values.isEmpty()) {
      return "";
    }
    return String.join(separator, values);
  }

  /** 지역 배열(text[])을 단일 문자열로 합친다. null/빈 값은 "". 프론트·명세는 단일 string. */
  static String joinRegion(List<String> region) {
    return joinText(region, "·");
  }

  /** 리스트 응답 필드를 null 대신 빈 배열로 내린다(프론트 계약). */
  static <T> List<T> nullSafe(List<T> value) {
    return value == null ? List.of() : value;
  }
}
