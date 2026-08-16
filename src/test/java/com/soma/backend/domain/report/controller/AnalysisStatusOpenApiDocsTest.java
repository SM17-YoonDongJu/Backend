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
 * <p>스키마 프로퍼티명은 snake_case다({@code report_id}·{@code accident_type} 등). 예전엔 springdoc·swagger-core가
 * Spring의 Jackson 3(SNAKE_CASE) 설정을 못 보고 자기 기본 Jackson 2 매퍼(camelCase)로 스키마를 만들어 실제
 * 응답과 어긋났는데, {@link com.soma.backend.global.config.OpenApiConfig}가 swagger-core의 정적 싱글턴
 * {@code Json31.mapper()}에 SNAKE_CASE 네이밍 전략을 직접 반영해 정정했다.
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
            hasItems("report_id", "analysis_state", "failed_documents")))
        // FAILED가 아니면 null인 필드들 — 3.1 유니온 타입에 "null"이 포함돼야 한다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failure_reason.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failure_message.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.reupload_guidance.type", hasItem("null")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failed_at.type", hasItem("null")))
        // 조건부 필수는 표준으로 표현할 수 없어 description에 조건을 서술한다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.failure_reason.description",
            containsString("FAILED")))
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.reupload_guidance.description",
            containsString("FAILED")))
        // 실패 문서 목록은 항상 배열이라 required이고 nullable이 아니다.
        .andExpect(jsonPath(ANALYSIS_STATUS + ".required", not(hasItem("failure_reason"))));
  }

  @Test
  @DisplayName("실패 문서 원소 스키마 — failure_reason은 required, 식별 필드(attachment_id·name)는 nullable이다")
  void failedDocumentSchemaDeclaresNullableIdentifiers() throws Exception {
    String failedDocument = "$.components.schemas.FailedDocument";

    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(failedDocument + ".required", hasItem("failure_reason")))
        .andExpect(jsonPath(failedDocument + ".properties.attachment_id.type", hasItem("null")))
        .andExpect(jsonPath(failedDocument + ".properties.name.type", hasItem("null")));
  }

  @Test
  @DisplayName("목록 카드·상세 스키마 — analysis_state는 required, 실패 사유·문구는 nullable이다")
  void listAndDetailSchemasCarryAnalysisFields() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(CARD + ".required", hasItem("analysis_state")))
        .andExpect(jsonPath(CARD + ".properties.analysis_failure_reason.type", hasItem("null")))
        .andExpect(jsonPath(CARD + ".properties.analysis_failure_message.type", hasItem("null")))
        .andExpect(jsonPath(DETAIL + ".required", hasItem("analysis_state")))
        .andExpect(jsonPath(DETAIL + ".properties.analysis_failure_reason.type", hasItem("null")))
        .andExpect(jsonPath(DETAIL + ".properties.analysis_failure_message.type", hasItem("null")));
  }

  @Test
  @DisplayName("analysis_state의 enum 값 목록에 5개 상태가 모두 실린다(3개 스키마 — allowableValues 드리프트 방지)")
  void analysisStateEnumCoversAllStates() throws Exception {
    String[] states = {"PROCESSING", "COMPLETED", "FAILED", "BLOCKED", "NEEDS_REUPLOAD"};

    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(ANALYSIS_STATUS + ".properties.analysis_state.enum", hasItems(states)))
        .andExpect(jsonPath(CARD + ".properties.analysis_state.enum", hasItems(states)))
        .andExpect(jsonPath(DETAIL + ".properties.analysis_state.enum", hasItems(states)));
  }

  @Test
  @DisplayName("신규 엔드포인트가 문서에 등록된다(GET /reports/{reportId}/analysis-status)")
  void analysisStatusEndpointIsDocumented() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/reports/{reportId}/analysis-status'].get").exists());
  }
}
