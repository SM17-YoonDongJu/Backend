package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 사건번호 파생({@link CaseNo}, #413) 단위 테스트 — 형식·대문자·날짜 기준·null 처리와 엔티티 파생 getter를 본다. */
class CaseNoTest {

  private static final UUID REPORT_ID = UUID.fromString("3f2b8c1e-5a6d-4e7f-8a9b-0c1d2e3f4a5b");

  @Test
  @DisplayName("생성일 yyyyMMdd + '-' + 리포트 ID 앞 8자리 대문자로 만든다")
  void formatsDateAndUpperCasedIdPrefix() {
    String caseNo = CaseNo.of(LocalDateTime.of(2026, 11, 19, 9, 30), REPORT_ID);

    assertThat(caseNo).isEqualTo("20261119-3F2B8C1E");
  }

  @Test
  @DisplayName("ID의 소문자 16진수는 모두 대문자가 되고, 하이픈 앞 8자리만 쓴다")
  void upperCasesHexAndUsesFirstEightCharacters() {
    UUID lowerHexId = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef");

    String caseNo = CaseNo.of(LocalDateTime.of(2026, 3, 4, 10, 0), lowerHexId);

    assertThat(caseNo).isEqualTo("20260304-ABCDEFAB").matches("\\d{8}-[0-9A-F]{8}");
  }

  @Test
  @DisplayName("날짜는 오늘이 아니라 넘긴 생성 시각의 날짜다 — 자정 직전·직후도 그 시각의 날짜를 따른다")
  void usesDateOfGivenCreatedAt() {
    assertThat(CaseNo.of(LocalDateTime.of(2025, 12, 31, 23, 59, 59, 999_999_999), REPORT_ID))
        .isEqualTo("20251231-3F2B8C1E");
    assertThat(CaseNo.of(LocalDateTime.of(2026, 1, 1, 0, 0), REPORT_ID))
        .isEqualTo("20260101-3F2B8C1E");
  }

  @Test
  @DisplayName("생성 시각이나 리포트 ID 중 하나라도 null이면 null이다(저장 전 엔티티·리포트 없는 채팅 검색 방)")
  void returnsNullWhenAnyInputIsNull() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 11, 19, 9, 30);

    assertThat(CaseNo.of(null, REPORT_ID)).isNull();
    assertThat(CaseNo.of(createdAt, null)).isNull();
    assertThat(CaseNo.of(null, null)).isNull();
  }

  @Test
  @DisplayName("Report.getCaseNo()는 저장 전이면 null이고, ID·생성 시각이 생기면 그 값으로 파생한다")
  void reportDerivesCaseNoFromCreatedAtAndId() {
    Report report = Report.createPending(UUID.randomUUID(), null, null, AccidentType.OTHER, null, "질문");

    assertThat(report.getCaseNo()).isNull();

    ReflectionTestUtils.setField(report, "id", REPORT_ID);
    ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.of(2026, 5, 20, 14, 0));

    assertThat(report.getCaseNo()).isEqualTo("20260520-3F2B8C1E");
  }
}
