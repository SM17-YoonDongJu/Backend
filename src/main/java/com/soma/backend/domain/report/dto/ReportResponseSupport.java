package com.soma.backend.domain.report.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

  /**
   * 분석 실패·재업로드 사유 코드 — 목록 카드·상세·폴링 세 응답의 단일 매핑 지점이다. FAILED면 대표 실패 사유
   * ({@code AnalysisFailureReason}), NEEDS_REUPLOAD면 확인된 재업로드 사유({@code ReuploadReason}), 그 밖(사유를 확인하지
   * 못한 NEEDS_REUPLOAD 포함)은 null이다. 두 출처는 상태로 배타적이다 — {@code reason}은 FAILED에만,
   * {@code reuploadCause}는 NEEDS_REUPLOAD에만 있다. 응답 코드는 각 enum의 상수 이름이다.
   */
  static @Nullable String analysisFailureReason(@Nullable ReportAnalysis analysis) {
    if (analysis == null) {
      return null;
    }
    if (analysis.reason() != null) {
      return analysis.reason().name();
    }
    if (analysis.reuploadCause() != null) {
      return analysis.reuploadCause().reason().name();
    }
    return null;
  }

  /** 사용자 노출 문구. FAILED·BLOCKED·NEEDS_REUPLOAD가 아니면 null(NEEDS_REUPLOAD는 재업로드 사유별 문구). */
  static @Nullable String analysisFailureMessage(@Nullable ReportAnalysis analysis) {
    return analysis == null ? null : analysis.failureMessage();
  }

  /** 재업로드 안내. FAILED·BLOCKED·NEEDS_REUPLOAD가 아니면 null. */
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

  /**
   * {@code reports.confidence_level}을 프론트 계약(HIGH|MEDIUM|LOW)에 맞춰 대문자로 정규화한다. null이면 null.
   * 상세·검수 화면·분석 근거 수치가 같은 값을 보여주도록 세 응답이 공유하는 단일 정규화 지점이다.
   */
  static @Nullable String confidenceLevel(@Nullable String confidenceLevel) {
    return confidenceLevel == null ? null : confidenceLevel.toUpperCase(Locale.ROOT);
  }

  /**
   * 배열 필드를 non-null로 정규화한다 — null이면 빈 배열, null 원소는 뺀다(순서는 보존). {@code List.copyOf}는 null
   * 원소에서 NPE를 던지므로 쓰지 않는다. 외부 계약(AI 저장 JSON)을 그대로 옮길 때 zod 검증이 깨지지 않게 하는 용도다.
   */
  static <T> List<T> nonNullElements(@Nullable List<T> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(Objects::nonNull).toList();
  }

  /** 맵 필드를 non-null로 정규화한다 — null이면 빈 맵, 키나 값이 null인 항목은 뺀다(입력 순서 보존, 수정 불가). */
  static <K, V> Map<K, V> nonNullEntries(@Nullable Map<K, V> entries) {
    if (entries == null) {
      return Map.of();
    }
    Map<K, V> copy = new LinkedHashMap<>();
    entries.forEach((key, value) -> {
      if (key != null && value != null) {
        copy.put(key, value);
      }
    });
    return Collections.unmodifiableMap(copy);
  }
}
