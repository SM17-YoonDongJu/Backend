package com.soma.backend.domain.report.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Q15 — 신규 분석 상태 필드가 OpenAPI 문서에 required/nullable로 반영됐는지 검증한다(CLAUDE.md OpenAPI 규칙).
 *
 * <p>springdoc 3.0.3은 OpenAPI 3.1을 생성하므로 nullable은 {@code nullable: true} 플래그가 아니라 JSON Schema
 * 유니온 타입({@code "type": ["string","null"]})으로 렌더링된다. 조건부 필수(FAILED일 때만 non-null)는
 * OpenAPI 표준으로 표현할 수 없어 {@code description}에 조건을 서술하는 것이 이 저장소의 관례이며,
 * 그 서술이 실제로 문서에 실렸는지까지 확인한다.
 *
 * <p><b>⚠️ 알려진 저장소 전역 드리프트(이 기능이 만든 것 아님):</b> 실제 응답 본문의 키는 snake_case인데
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}) springdoc이 만드는 스키마의 property 이름은
 * <b>camelCase</b>다({@code reportId}·{@code accidentType} 등 기존 스키마도 전부 동일). 그래서 이 테스트는
 * 문서의 현재 사실대로 camelCase로 단언한다 — 스키마 이름 규칙을 고치면(전역 설정) 이 단언도 함께 바뀐다.
 * 프론트가 문서로 타입을 생성하면 필드명이 어긋나므로 별도 이슈로 다뤄야 한다.
 */
@SpringBootTest(properties = "app.docs.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 상태 OpenAPI 문서 테스트")
class AnalysisStatusOpenApiDocsTest {

  private static final String ANALYSIS_STATUS = "$.components.schemas.ReportAnalysisStatusResponse";
  private static final String CARD = "$.components.schemas.Card";
  private static final String DETAIL = "$.components.schemas.CustomerReportDetailResponse";

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("전용 응답 스키마 — 항상 존재하는 필드는 required, 조건부 필드는 nullable 유니온 타입이다")
  void analysisStatusSchemaDeclaresRequiredAndNullable() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(ANALYSIS_STATUS + ".required",
            hasItems("reportId", "analysisState", "failedDocuments")))
        // FAILED가 아니면 null인 필드들 — 3.1 유니온 타입에 "null"이 포함돼야 한다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failureReason.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failureMessage.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.reuploadGuidance.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failedAt.type", hasItem("null")))
        // 조건부 필수는 표준으로 표현할 수 없어 description에 조건을 서술한다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failureReason.description",
            containsString("FAILED")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.reuploadGuidance.description",
            containsString("FAILED")))
        // 실패 문서 목록은 항상 배열이라 required이고 nullable이 아니다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".required", not(hasItem("failureReason"))));
  }

  @Test
  @DisplayName("실패 문서 원소 스키마 — failure_reason은 required, 식별 필드(attachment_id·name)는 nullable이다")
  void failedDocumentSchemaDeclaresNullableIdentifiers() throws Exception {
    String failedDocument = "$.components.schemas.FailedDocument";

    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(failedDocument + ".required", hasItem("failureReason")))
        .andExpect(jsonPath(failedDocument + ".properties.attachmentId.type", hasItem("null")))
        .andExpect(jsonPath(failedDocument + ".properties.name.type", hasItem("null")));
  }

  @Test
  @DisplayName("목록 카드·상세 스키마 — analysis_state는 required, 실패 사유·문구는 nullable이다")
  void listAndDetailSchemasCarryAnalysisFields() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(CARD + ".required", hasItem("analysisState")))
        .andExpect(jsonPath(CARD + ".properties.analysisFailureReason.type", hasItem("null")))
        .andExpect(jsonPath(CARD + ".properties.analysisFailureMessage.type", hasItem("null")))
        .andExpect(jsonPath(DETAIL + ".required", hasItem("analysisState")))
        .andExpect(jsonPath(DETAIL + ".properties.analysisFailureReason.type", hasItem("null")))
        .andExpect(jsonPath(DETAIL + ".properties.analysisFailureMessage.type", hasItem("null")));
  }

  @Test
  @DisplayName("신규 엔드포인트가 문서에 등록된다(GET /reports/{reportId}/analysis-status)")
  void analysisStatusEndpointIsDocumented() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/reports/{reportId}/analysis-status'].get").exists());
  }
}
