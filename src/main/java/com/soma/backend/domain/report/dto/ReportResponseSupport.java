package com.soma.backend.domain.report.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.report.entity.AnalysisState;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReuploadGuidance;

/**
 * report 응답 DTO 공통 포맷 헬퍼. 프론트 계약 정합용 — 지역(text[])을 단일 문자열로 조인하고,
 * 리스트 응답 필드는 null 대신 빈 배열로, 제목은 미생성 시 기본 문구로 내린다.
 */
final class ReportResponseSupport {

  /** title은 AI 초안이 채우는 값이라 생성 전이면 null일 수 있다. 프론트 계약(non-null)용 기본 문구. */
  static final String TITLE_PLACEHOLDER = "제목 없음";

  private ReportResponseSupport() {
  }

  /** 리포트 제목 계약 정합 — null/공백이면 {@link #TITLE_PLACEHOLDER}로 내린다(프론트 계약은 항상 non-null string). */
  static String title(String title) {
    return (title == null || title.isBlank()) ? TITLE_PLACEHOLDER : title;
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

  /**
   * 분석 처리 상태(AnalysisState) 매핑. {@code null}이면 PROCESSING으로 내린다 — 실패 저널({@code ai} 스키마)
   * 조회가 불가능해 degrade된 경우까지 포함해 이 필드는 항상 non-null이다(design.md §8 E14).
   * 목록 카드·상세·전용 엔드포인트가 공유하는 단일 매핑 지점이다.
   */
  static String analysisState(@Nullable ReportAnalysis analysis) {
    return analysis == null ? AnalysisState.PROCESSING.name() : analysis.state().name();
  }

  /** 대표 실패 사유. 실패(FAILED)가 아니면 null. */
  static @Nullable String analysisFailureReason(@Nullable ReportAnalysis analysis) {
    return analysis == null || analysis.reason() == null ? null : analysis.reason().name();
  }

  /** 사용자 노출 실패 문구. 실패(FAILED)가 아니면 null. */
  static @Nullable String analysisFailureMessage(@Nullable ReportAnalysis analysis) {
    return analysis == null ? null : analysis.failureMessage();
  }

  /** 재업로드 안내. 실패(FAILED)가 아니면 null. */
  static @Nullable String reuploadGuidance(@Nullable ReportAnalysis analysis) {
    ReuploadGuidance guidance = analysis == null ? null : analysis.reuploadGuidance();
    return guidance == null ? null : guidance.name();
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
