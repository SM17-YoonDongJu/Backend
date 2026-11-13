package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * 분석 근거 수치 응답의 OpenAPI 스키마 검증(design.md §11 T8, CLAUDE.md OpenAPI 규칙). 프론트가 생성 타입 + zod
 * 응답 검증을 쓰므로 스키마와 실제 응답(T4·T5)이 어긋나면 패널 전체가 파싱 실패한다.
 *
 * <p>확인하는 것: 스키마 키가 실제 응답과 같은 snake_case이고 판례 건수 키는 {@code case}다(springdoc이 쓰는
 * swagger-core 정적 매퍼도 {@code @JsonProperty} 명시 이름을 네이밍 전략으로 바꾸지 않는다). 배열·맵은 required,
 * 스칼라는 nullable 유니온 타입이다. nullable 문자열에는 enum이 없다(값 목록은 description). 중첩 record가
 * {@code Metrics} 접두 이름으로 각자 컴포넌트가 돼 다른 DTO의 같은 단순 이름과 합쳐지지 않는다.
 * {@link AnalysisStatusOpenApiDocsTest}와 같은 설정이라 컨텍스트를 공유한다.
 */
@SpringBootTest(properties = "app.docs.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("분석 근거 수치 OpenAPI 문서 테스트")
class ReportAnalysisMetricsOpenApiDocsTest {

  private static final String SCHEMAS = "$.components.schemas.";
  private static final String RESPONSE = SCHEMAS + "ReportAnalysisMetricsResponse";

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("엔드포인트가 문서에 등록되고 200 응답 스키마가 실제로 연결된다(스키마 생성 실패 시 content가 비어 버린다)")
  void endpointIsDocumentedWithResponseSchema() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/reports/{reportId}/analysis-metrics'].get").exists())
        .andExpect(jsonPath("$.paths['/reports/{reportId}/analysis-metrics'].get.responses['200']"
            + ".content['*/*'].schema['$ref']", endsWith("/ApiResponseReportAnalysisMetricsResponse")))
        .andExpect(jsonPath(SCHEMAS + "ApiResponseReportAnalysisMetricsResponse.properties.data['$ref']",
            endsWith("/ReportAnalysisMetricsResponse")));
  }

  /**
   * {@code by_label}({@code Map<String, Integer>})은 정수 값을 갖는 객체로 문서화된다. Map 프로퍼티는 swagger-core가
   * 스키마를 복제할 때 {@code OpenApiConfig}의 전역 SNAKE_CASE 때문에 실패해 응답 스키마 전체가 빠졌다(DTO 주석 참고).
   * 값 스키마의 키 표기({@code additionalProperties}/{@code additional_properties})는 그 전역 설정을 따르므로 표기와
   * 무관하게 확인한다.
   */
  @Test
  @DisplayName("by_label은 정수 값을 갖는 객체(맵)로 문서화된다")
  void byLabelIsDocumentedAsIntegerMap() throws Exception {
    String body = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

    Map<String, Object> byLabel = JsonPath.read(body, SCHEMAS + "MetricsPiiMasked.properties.by_label");
    Object valueSchema = byLabel.containsKey("additionalProperties")
        ? byLabel.get("additionalProperties")
        : byLabel.get("additional_properties");

    assertThat(byLabel.get("type")).isEqualTo("object");
    assertThat(valueSchema).isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) valueSchema).get("type")).isEqualTo("integer");
  }

  /**
   * 중첩 객체의 nullable이 지금 생성기가 내는 {@code {"type": "null", "$ref": ...}} 모양인지 고정한다.
   *
   * <p><b>이 모양이 올바른 계약이라서 고정하는 게 아니다.</b> OpenAPI 3.1(JSON Schema 2020-12)에서는 {@code $ref}가
   * 형제 키워드와 함께 적용되므로, 이 스키마는 객체를 거부하고 null만 통과시키는 모순이다. 올바른 모양은
   * {@code anyOf: [{$ref}, {type: null}]}다. 그런데 swagger-core 2.2.47은 {@code $ref}로 참조하는 중첩 record의
   * {@code nullable = true}를 이 모양으로만 내보내고, {@code anyOf} 애노테이션 조합으로도 바뀌지 않는다
   * (CLAUDE.md OpenAPI 문서화 규칙의 "알려진 한계").
   *
   * <p>프론트는 코드 생성 단계의 {@code expandNullableRefs} 패치로 바로 이 모양을 {@code anyOf}로 펼쳐 쓴다. 이 테스트는
   * 생성기 출력이 바뀌어 그 패치의 전제가 깨지는 순간을 잡으려고 둔 것이다. 문서 전체를 후처리해 {@code anyOf}로
   * 바로잡는 일은 프론트 생성물 전체에 영향이 있어 OpenAPI 문서 정리 후속에서 프론트와 함께 한다. 그때 이 단언도
   * {@code anyOf} 모양으로 바꾼다.
   */
  @Test
  @DisplayName("중첩 객체 nullable은 현재 {type: null, $ref} 형태다(swagger-core 알려진 한계 — 프론트 패치의 전제)")
  void nestedNullableObjectShape() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(RESPONSE + ".properties.input.type").value("null"))
        .andExpect(jsonPath(RESPONSE + ".properties.input['$ref']", endsWith("/MetricsInput")))
        .andExpect(jsonPath(SCHEMAS + "MetricsRetrieval.properties.case_funnel['$ref']",
            endsWith("/MetricsCaseFunnel")));
  }

  @Test
  @DisplayName("최상위 — available만 required이고 사유·버전은 nullable 유니온 타입, 사유에는 enum이 없다")
  void topLevelRequiredAndNullable() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(RESPONSE + ".required", hasItem("available")))
        .andExpect(jsonPath(RESPONSE + ".required", not(hasItem("unavailable_reason"))))
        .andExpect(jsonPath(RESPONSE + ".properties.unavailable_reason.type", hasItem("null")))
        .andExpect(jsonPath(RESPONSE + ".properties.unavailable_reason.enum").doesNotExist())
        .andExpect(jsonPath(RESPONSE + ".properties.version.type", hasItem("null")))
        .andExpect(jsonPath(RESPONSE + ".properties.unavailableReason").doesNotExist());
  }

  @Test
  @DisplayName("판례 건수 키는 case다(case_law·caseLaw 아님)")
  void evidenceCaseKey() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(SCHEMAS + "MetricsEvidence.properties.case").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsEvidence.properties.case.type", hasItem("null")))
        .andExpect(jsonPath(SCHEMAS + "MetricsEvidence.properties.case_law").doesNotExist())
        .andExpect(jsonPath(SCHEMAS + "MetricsEvidence.properties.caseLaw").doesNotExist());
  }

  @Test
  @DisplayName("배열·맵은 required, 스칼라는 snake_case 키의 nullable 유니온 타입이다")
  void arraysRequiredScalarsNullable() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(SCHEMAS + "MetricsInput.required", hasItem("doc_types")))
        .andExpect(jsonPath(SCHEMAS + "MetricsInput.properties.document_count.type", hasItem("null")))
        .andExpect(jsonPath(SCHEMAS + "MetricsInput.properties.documentCount").doesNotExist())
        .andExpect(jsonPath(SCHEMAS + "MetricsPiiMasked.required", hasItem("by_label")))
        .andExpect(jsonPath(SCHEMAS + "MetricsPipeline.required", hasItems("failed_nodes", "nodes")))
        .andExpect(jsonPath(SCHEMAS + "MetricsConfidence.required", hasItem("reasons")))
        .andExpect(jsonPath(SCHEMAS + "MetricsDisability.required", hasItem("reclassified")))
        .andExpect(jsonPath(SCHEMAS + "MetricsClauseCitations.required", hasItem("ungrounded")))
        .andExpect(jsonPath(SCHEMAS + "MetricsRetrieval.properties.disability_schedule_source.type", hasItem("null")))
        .andExpect(jsonPath(SCHEMAS + "MetricsRetrieval.properties.disability_schedule_source.enum").doesNotExist());
  }

  @Test
  @DisplayName("중첩 record가 Metrics 접두 이름으로 각자 컴포넌트가 된다(다른 DTO와 병합되지 않는다)")
  void nestedRecordsAreSeparateComponents() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(SCHEMAS + "MetricsJudge.properties.checked_chars").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsScoreStats.properties.min").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsCaseFunnel.properties.kept").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsReclassification.properties.injury").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsPipelineNode.properties.ms").exists())
        .andExpect(jsonPath(SCHEMAS + "MetricsVerification.properties.clause_citations").exists());
  }
}
