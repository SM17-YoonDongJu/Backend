package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * POST /reports가 받은 보험사 제시액(offered_amount)이 reports에도 저장되는지 고정하는 회귀 테스트(이슈 #408).
 *
 * <p>접수 제시액이 {@code user_claims.offered_amount}에만 들어가고 {@code reports.offered_amount}는 항상 NULL이었다.
 * AI 워커는 리포트 쪽 값을 읽어 청구 가능 범위를 계산하므로 claimed_min/max가 0~0으로 저장됐고, 고객 상세
 * {@code GET /reports/{id}}의 {@code offered_amount}도 null로 내려갔다. 그래서 요청 → 두 테이블의 원본 컬럼 → 상세
 * 응답을 한 흐름으로 확인한다. 컬럼은 {@link JdbcTemplate}으로 직접 읽는다 — AI 워커가 읽는 것도 이 컬럼이다.
 *
 * <p>실제 test_db를 쓰며 {@code @Transactional}로 종료 시 롤백한다. 상세 본 조회({@code CustomerReportDetailReader},
 * readOnly·기본 전파)는 테스트 트랜잭션에 합류해 커밋 전 행을 본다. 분석 상태 판정은 test_db에 ai 스키마가 없어
 * PROCESSING으로 낮춰지지만 이 테스트는 제시액만 본다. {@code documents}가 비어 있어 아웃박스 적재도 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("리포트 생성 제시액(offered_amount) 회귀 테스트(#408)")
class ReportCreationOfferedAmountIntegrationTest {

  /** dev에서 재현된 요청값(리포트 b79a441a…, 이슈 #408). */
  private static final int OFFERED_AMOUNT = 480_000;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    userId = userRepository.save(
        User.create("제시액유저", LocalDate.of(1990, 5, 5), "male", null, null, Role.USER, List.of("서울")))
        .getId();
  }

  // 0도 넣는다 — 프론트는 null이면 "제안 없음", 0이면 금액으로 그리므로 0을 null로 바꾸는 정규화가 끼면 잡아야 한다.
  @ParameterizedTest(name = "제시액 {0}원")
  @ValueSource(ints = {OFFERED_AMOUNT, 0})
  @DisplayName("제시액을 넣어 접수하면 reports·user_claims 컬럼과 상세 응답에 같은 값이 담긴다")
  void createReport_storesOfferedAmountOnReport(int offeredAmount) throws Exception {
    UUID reportId = createReport(offeredAmount);

    assertThat(reportOfferedAmount(reportId)).isEqualTo(offeredAmount);
    assertThat(claimOfferedAmount(reportId)).isEqualTo(offeredAmount);
    mockMvc.perform(get("/reports/{reportId}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.offered_amount").value(offeredAmount));
  }

  @Test
  @DisplayName("제시액 없이 접수하면 reports 컬럼과 상세 응답 모두 null이다 — 0으로 바꾸지 않는다")
  void createReport_withoutOfferedAmount_leavesNull() throws Exception {
    UUID reportId = createReport(null);

    assertThat(reportOfferedAmount(reportId)).isNull();
    assertThat(claimOfferedAmount(reportId)).isNull();
    mockMvc.perform(get("/reports/{reportId}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.offered_amount").value(nullValue()));
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  /** 제시액이 null이면 키 자체를 뺀다 — 미입력 필드를 보내지 않는 클라이언트와 같은 요청이다. */
  private String requestBody(Integer offeredAmount) {
    String offeredAmountField = offeredAmount == null ? "" : "\"offered_amount\": " + offeredAmount + ",";
    return """
        {
          "accident_type": "medical_indemnity",
          "accident_date": "2026-01-01",
          "diagnosis": ["급성 충수염"],
          %s
          "hospitalizations": [],
          "description": "사고 경위입니다",
          "question": "보장 여부가 궁금합니다",
          "documents": []
        }
        """.formatted(offeredAmountField);
  }

  private UUID createReport(Integer offeredAmount) throws Exception {
    MvcResult result = mockMvc.perform(post("/reports")
            .with(authenticatedAsOwner())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody(offeredAmount)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.report_id").isNotEmpty())
        .andReturn();
    entityManager.flush();
    entityManager.clear();
    return reportIdOf(result);
  }

  private UUID reportIdOf(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    int start = body.indexOf("\"report_id\":\"") + "\"report_id\":\"".length();
    return UUID.fromString(body.substring(start, start + 36));
  }

  private Integer reportOfferedAmount(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "SELECT offered_amount FROM reports WHERE id = ?", Integer.class, reportId);
  }

  private Integer claimOfferedAmount(UUID reportId) {
    return jdbcTemplate.queryForObject(
        "SELECT c.offered_amount FROM user_claims c JOIN reports r ON r.claim_id = c.id WHERE r.id = ?",
        Integer.class, reportId);
  }
}
