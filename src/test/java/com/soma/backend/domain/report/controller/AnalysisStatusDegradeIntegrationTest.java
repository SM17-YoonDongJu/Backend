package com.soma.backend.domain.report.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * Q12 — {@code ai.ocr_job_failures}를 읽을 수 없을 때의 degrade 검증(design.md §8 E14).
 *
 * <p>GRANT 미적용·AI 마이그레이션 미배포 상황을 <b>스키마를 실제로 삭제해</b> 재현한다(모킹이 아니라 진짜
 * SQL 실패라야 {@code REQUIRES_NEW} 분리가 의미 있는지 확인된다 — 같은 트랜잭션에서 JDBC 예외를 삼키면
 * 커밋 시 {@code UnexpectedRollbackException}으로 목록이 통째로 500이 된다).
 *
 * <p>검증 대상은 셋이다: <b>앱은 기동하고</b>({@code @Subselect}라 부팅 검증 대상이 아님) <b>목록·상세는
 * 200 + PROCESSING</b>으로 낮춰 내리며 <b>전용 폴링 엔드포인트만 500</b>이다. 클래스 종료 시 계약 스키마를
 * 원복한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema-drop.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@DisplayName("ai 계약 테이블 미가용 시 degrade 테스트")
class AnalysisStatusDegradeIntegrationTest {

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "DEG-" + UUID.randomUUID().toString().substring(0, 12))).getId();
  }

  @AfterEach
  void cleanUp() {
    // 트랜잭션 롤백이 없으므로(REQUIRES_NEW 가시성 때문에 커밋 경로로 검증한다) 직접 지운다.
    reportRepository.deleteAll();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("Q12 — 목록 조회는 200을 유지하고 분석 상태만 PROCESSING으로 degrade한다")
  void listDegradesToProcessing() throws Exception {
    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("PROCESSING"));
  }

  @Test
  @DisplayName("Q12 — 상세 조회도 200을 유지하고 분석 상태만 PROCESSING으로 degrade한다")
  void detailDegradesToProcessing() throws Exception {
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("PROCESSING"));
  }

  @Test
  @DisplayName("Q12 — 전용 analysis-status 엔드포인트는 degrade하지 않고 500을 낸다(거짓 PROCESSING 금지)")
  void dedicatedEndpointFailsLoudly() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isInternalServerError());
  }

  /**
   * E13 — <b>알려진 한계를 고정하는 테스트다(기대 동작을 승인하는 것이 아니다).</b>
   *
   * <p>NEEDS_REUPLOAD·BLOCKED 판정은 {@code reports.status}만 보면 되고 {@code ai} 저널이 전혀 필요 없는데도,
   * 목록·상세는 저널 조회가 실패하면 리포트 단위 판정을 통째로 버리고 PROCESSING으로 낮춘다
   * ({@code ReportQueryService.analysesOrDegrade}가 빈 맵을 반환 → {@code ReportResponseSupport}가 PROCESSING 폴백).
   * 즉 GRANT 미적용 환경에서는 이 기능이 없애려던 "무음 정지"가 목록·상세에서 그대로 재현된다.
   *
   * <p>NEEDS_REUPLOAD가 만든 회귀가 아니라 BLOCKED에도 있던 기존 한계라 이번 스코프에서 고치지 않는다
   * (design.md §10 E13). 이 테스트는 그 한계를 명시적으로 고정해, 나중에 degrade를 리포트 단위 판정
   * 폴백({@code ReportAnalysis.of(report, List.of())})으로 개선할 때 <b>이 단언이 깨지는 것이 곧 개선 신호</b>가
   * 되게 한다.
   */
  @Test
  @DisplayName("E13 알려진 한계 — 저널이 안 읽히면 NEEDS_REUPLOAD도 목록에서 PROCESSING으로 degrade된다")
  void needsReuploadAlsoDegradesToProcessing() throws Exception {
    // Given: AI 워커가 원시 SQL로 세팅하는 종료 상태를 같은 값으로 만든다(전이표를 거치지 않는 진입).
    Report report = Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "DEG-" + UUID.randomUUID().toString().substring(0, 12));
    ReflectionTestUtils.setField(report, "status", ReportStatus.NEEDS_REUPLOAD);
    UUID needsReuploadId = reportRepository.save(report).getId();

    // When & Then: 상태 필터로 해당 리포트만 좁혀 본다(필터 자체는 enum 추가만으로 동작한다 — E7).
    mockMvc.perform(get("/reports").param("status", "NEEDS_REUPLOAD").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(needsReuploadId.toString()))
        // 생명주기 status는 저널과 무관하므로 degrade에도 살아남는다 — 분석 축만 PROCESSING으로 떨어진다.
        .andExpect(jsonPath("$.data.list[0].status").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("PROCESSING"));
  }
}
