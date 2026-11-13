package com.soma.backend.domain.adjuster.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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
 * 사정사 마이페이지 응답(GET /adjusters/me/mypage)의 자격 스키마가 사정사 상세의 동명 스키마에 덮이지 않는지 검증한다.
 *
 * <p>swagger-core는 클래스 단순 이름으로 스키마를 등록한다. 마이페이지의 {@code Certification}이 사정사 상세의
 * 동명 record와 겹쳐 문서에 상세 쪽만 남았고, 프론트 생성 zod가 그 스키마(registration_no 필수)로 마이페이지
 * 응답을 검증하다 실패했다. 마이페이지 record에 고유 이름을 붙였으니 두 스키마가 각자 자기 필드를 가져야 한다.
 *
 * <p>{@code Stats}도 검수 이력과 이름이 겹치지만 여기서 다루지 않는다. {@code GET /adjusters/me/reviewed-reports}의
 * 응답 스키마가 문서에서 빠져 있어({@code content: {}}) 프론트가 지금의 {@code Stats}로 검수 이력 타입을 맞추고
 * 있기 때문이다. 이름 분리는 그 누락을 고칠 때 같이 한다.
 */
@SpringBootTest(properties = "app.docs.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("사정사 마이페이지 OpenAPI 문서 테스트")
class AdjusterMyPageOpenApiDocsTest {

  private static final String SCHEMAS = "$.components.schemas.";
  private static final String MY_PAGE = SCHEMAS + "AdjusterMyPageResponse";
  private static final String MY_PAGE_CERTIFICATION = SCHEMAS + "AdjusterMyPageCertification";

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("마이페이지 응답은 고유 이름의 자격 스키마를 참조한다")
  void myPageReferencesOwnCertificationSchema() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(MY_PAGE + ".properties.certification.$ref")
            .value("#/components/schemas/AdjusterMyPageCertification"));
  }

  @Test
  @DisplayName("마이페이지 자격 스키마 — 상세와 같은 registration_no·verified_at을 nullable로 갖고 license_no는 deprecated다")
  void myPageCertificationSchemaHasOwnFields() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(MY_PAGE_CERTIFICATION + ".required", hasItems("activity_region", "created_at")))
        .andExpect(jsonPath(MY_PAGE_CERTIFICATION + ".properties.registration_no.type", hasItem("null")))
        .andExpect(jsonPath(MY_PAGE_CERTIFICATION + ".properties.verified_at.type", hasItem("null")))
        .andExpect(jsonPath(MY_PAGE_CERTIFICATION + ".properties.license_no.type", hasItem("null")))
        .andExpect(jsonPath(MY_PAGE_CERTIFICATION + ".properties.license_no.deprecated").value(true));
  }

  @Test
  @DisplayName("동명 스키마였던 사정사 상세 Certification은 자기 필드를 그대로 유지한다")
  void detailCertificationSchemaKeepsItsFields() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(SCHEMAS + "AdjusterDetailResponse.properties.certification.$ref")
            .value("#/components/schemas/Certification"))
        .andExpect(jsonPath(SCHEMAS + "Certification.required", hasItem("registration_no")))
        .andExpect(jsonPath(SCHEMAS + "Certification.properties.verified_at").exists())
        .andExpect(jsonPath(SCHEMAS + "Certification.properties.license_no").doesNotExist());
  }
}
