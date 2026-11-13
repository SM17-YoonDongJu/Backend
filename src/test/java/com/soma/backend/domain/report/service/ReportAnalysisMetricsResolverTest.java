package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsDisability;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsInput;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsJudge;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsRetrieval;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportDraftMetricsViewFixture;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * {@link ReportAnalysisMetricsResolver} 단위 테스트(design.md §11 T3) — 분류(NO_DRAFT·LEGACY_REPORT·UNAVAILABLE),
 * 허용 목록 바인딩의 관용성(모르는 키·누락·명시적 null), 버전 게이트, 원인별 카운터, 로그에 값·메시지가 남지 않는지.
 *
 * <p>매퍼는 운영과 같은 조건(Jackson 3 기본값 + {@code SNAKE_CASE})으로 직접 만든다. Spring 전역 매퍼로 실제
 * 바인딩·직렬화되는지는 {@code ReportAnalysisMetricsIntegrationTest}가 확인한다. 로그는 레벨 패턴에 의존하지 않게
 * reportId로 줄을 골라 확인한다(테스트마다 새 UUID라 다른 테스트 로그와 섞이지 않는다).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("ReportAnalysisMetricsResolver 단위 테스트")
class ReportAnalysisMetricsResolverTest {

  private static final String GOLDEN_V1 = ReportDraftMetricsViewFixture.GOLDEN_V1_METRICS;

  @Mock
  private ReportDraftMetricsReader reportDraftMetricsReader;

  private final JsonMapper jsonMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  private SimpleMeterRegistry meterRegistry;
  private ReportAnalysisMetricsResolver resolver;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    resolver = new ReportAnalysisMetricsResolver(reportDraftMetricsReader, jsonMapper, meterRegistry);
    reportId = UUID.randomUUID();
  }

  private ReportAnalysisMetricsResponse resolveStored(String metricsJson, String confidenceLevel) {
    given(reportDraftMetricsReader.findByReportId(reportId))
        .willReturn(Optional.of(ReportDraftMetricsViewFixture.of(reportId, metricsJson)));
    return resolver.resolve(reportId, confidenceLevel);
  }

  private double unavailable(String cause) {
    return meterRegistry.get(ReportAnalysisMetricsResolver.UNAVAILABLE_METRIC).tag("cause", cause).counter().count();
  }

  private double unavailableTotal() {
    return meterRegistry.find(ReportAnalysisMetricsResolver.UNAVAILABLE_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  /** 이 테스트의 reportId가 찍힌 로그 줄 중 WARN 줄. 레벨 패턴(평문·JSON)과 무관하게 "WARN" 토큰이 같은 줄에 있다. */
  private List<String> warnLines(CapturedOutput output) {
    return output.getAll().lines()
        .filter(line -> line.contains(reportId.toString()))
        .filter(line -> line.contains("WARN"))
        .toList();
  }

  private static void assertUnavailable(ReportAnalysisMetricsResponse response, String reason) {
    assertThat(response.available()).isFalse();
    assertThat(response.unavailableReason()).isEqualTo(reason);
    assertThat(response.version()).isNull();
    assertThat(response.confidence()).isNull();
    assertThat(response.input()).isNull();
    assertThat(response.retrieval()).isNull();
    assertThat(response.verification()).isNull();
    assertThat(response.pipeline()).isNull();
  }

  // --- 1~7: v1 바인딩 ---

  @Test
  @DisplayName("1. 골든 v1은 available이고 모든 필드가 매핑된다(case 키·라벨별 건수·사유 순서·컬럼 등급)")
  void goldenV1MapsEveryField(CapturedOutput output) {
    ReportAnalysisMetricsResponse response = resolveStored(GOLDEN_V1, "medium");

    assertThat(response.available()).isTrue();
    assertThat(response.unavailableReason()).isNull();
    assertThat(response.version()).isEqualTo(1);
    assertThat(response.confidence().level()).isEqualTo("MEDIUM");
    assertThat(response.confidence().reasons()).containsExactly(
        "가입금액 미보유 — 제시액 기준 어림 범위", "근거 미확인 주장 1건", "인용 검증 범위 일부(96%)",
        "근거에 없는 조항 인용 1건", "후유장해 지급률 추정 불확실");

    MetricsInput input = response.input();
    assertThat(input.documentCount()).isEqualTo(3);
    assertThat(input.docTypes()).containsExactly("policy", "diagnosis", "medical_receipt");
    assertThat(input.ocrConfidence().min()).isEqualTo(0.93);
    assertThat(input.ocrConfidence().avg()).isEqualTo(0.94);
    assertThat(input.docTypeConfidence().min()).isEqualTo(0.8);
    assertThat(input.docTypeConfidence().avg()).isEqualTo(0.9);
    assertThat(input.piiMasked().total()).isEqualTo(6);
    assertThat(input.piiMasked().byLabel()).hasSize(3)
        .containsEntry("이름", 3).containsEntry("전화번호", 2).containsEntry("주민등록번호", 1);
    assertThat(input.piiMasked().documents()).isEqualTo(4);
    assertThat(input.piiMasked().freeText()).isEqualTo(2);

    MetricsRetrieval retrieval = response.retrieval();
    assertThat(retrieval.policyMatched()).isTrue();
    assertThat(retrieval.evidence().terms()).isEqualTo(2);
    assertThat(retrieval.evidence().schedule()).isEqualTo(1);
    assertThat(retrieval.evidence().caseLaw()).isEqualTo(2);
    assertThat(retrieval.caseFunnel().candidates()).isEqualTo(12);
    assertThat(retrieval.caseFunnel().deduped()).isEqualTo(9);
    assertThat(retrieval.caseFunnel().relevant()).isEqualTo(2);
    assertThat(retrieval.caseFunnel().kept()).isEqualTo(2);
    assertThat(retrieval.disabilityScheduleSource()).isEqualTo("terms");

    MetricsDisability disability = response.verification().disability();
    assertThat(disability.extracted()).isEqualTo(2);
    assertThat(disability.verified()).isEqualTo(2);
    assertThat(disability.combinedRate()).isEqualTo(20.0);
    assertThat(disability.confidence()).isEqualTo("low");
    assertThat(disability.pendingReeval()).isFalse();
    assertThat(disability.reclassified()).singleElement().satisfies(item -> {
      assertThat(item.injury()).isEqualTo("추간판 탈출");
      assertThat(item.from()).isEqualTo(30.0);
      assertThat(item.to()).isEqualTo(20.0);
    });
    assertThat(response.verification().clauseCitations().cited()).isEqualTo(3);
    assertThat(response.verification().clauseCitations().grounded()).isEqualTo(2);
    assertThat(response.verification().clauseCitations().ungrounded()).containsExactly("제99조");
    assertThat(response.verification().clauseCitations().statuteMentions()).isEqualTo(1);
    MetricsJudge judge = response.verification().judge();
    assertThat(judge.ran()).isTrue();
    assertThat(judge.unavailable()).isFalse();
    assertThat(judge.flagged()).isEqualTo(1);
    assertThat(judge.checkedChars()).isEqualTo(12000);
    assertThat(judge.totalChars()).isEqualTo(12480);
    assertThat(judge.coverage()).isEqualTo(0.96);
    assertThat(judge.evidenceChunks()).isEqualTo(5);

    assertThat(response.pipeline().nodeCount()).isEqualTo(12);
    assertThat(response.pipeline().failedNodes()).isEmpty();
    assertThat(response.pipeline().totalMs()).isEqualTo(73043);
    assertThat(response.pipeline().nodes()).extracting("node", "ms", "status")
        .containsExactly(
            tuple("load_context", 140, "ok"),
            tuple("case_search", 900, "ok"));

    assertThat(unavailableTotal()).isZero();
    // 컬럼(medium)과 저장 등급(MEDIUM)은 대소문자만 달라 같은 값이다 — 경고하지 않는다.
    assertThat(warnLines(output)).isEmpty();
  }

  @Test
  @DisplayName("2. 모르는 키는 어느 깊이에 있든 무시되고(최상위·그룹·배열 원소) 응답 직렬화에도 나가지 않는다")
  void unknownKeysAreIgnoredAtEveryDepth() {
    String json = """
        {"version": 1, "future": {"x": 1},
         "input": {"document_count": 3, "doc_types": ["policy"], "new_field": "secret-new-field"},
         "pipeline": {"nodes": [{"node": "load_context", "ms": 1, "status": "ok", "extra": "secret-extra"}],
                      "node_count": 1, "failed_nodes": [], "total_ms": 1}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, "LOW");

    assertThat(response.available()).isTrue();
    assertThat(response.input().documentCount()).isEqualTo(3);
    assertThat(response.pipeline().nodes()).extracting("node").containsExactly("load_context");
    assertThat(jsonMapper.writeValueAsString(response))
        .doesNotContain("future", "new_field", "secret-new-field", "extra", "secret-extra");
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("3. 그룹이 통째로 없으면 그 그룹만 null이고 confidence는 non-null(사유는 빈 배열)이다")
  void missingGroupIsNull() {
    String json = """
        {"version": 1, "input": {"document_count": 1, "doc_types": ["diagnosis"]}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, "high");

    assertThat(response.available()).isTrue();
    assertThat(response.input().documentCount()).isEqualTo(1);
    assertThat(response.retrieval()).isNull();
    assertThat(response.verification()).isNull();
    assertThat(response.pipeline()).isNull();
    assertThat(response.confidence()).isNotNull();
    assertThat(response.confidence().level()).isEqualTo("HIGH");
    assertThat(response.confidence().reasons()).isEmpty();
  }

  /** 하위 객체가 빠진 경우와 명시적 null이 둘 다 예외 없이 null로 바인딩되는지 고정한다(#396 완료 조건). */
  @Test
  @DisplayName("4. 하위 객체가 빠지거나(case_funnel) 명시적 null(ocr_confidence)이면 null이다")
  void missingOrNullSubObjectIsNull() {
    String json = """
        {"version": 1,
         "input": {"document_count": 2, "doc_types": [], "ocr_confidence": null,
                   "doc_type_confidence": {"min": 0.5, "avg": 0.6}},
         "retrieval": {"policy_matched": false, "evidence": {"terms": 1, "schedule": 0, "case": 0}}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, null);

    assertThat(response.input().ocrConfidence()).isNull();
    assertThat(response.input().docTypeConfidence().min()).isEqualTo(0.5);
    assertThat(response.retrieval().caseFunnel()).isNull();
    assertThat(response.retrieval().disabilityScheduleSource()).isNull();
    assertThat(response.retrieval().evidence().caseLaw()).isZero();
  }

  /** Jackson 3은 원시 타입에 null이 오면 바인딩 전체가 실패한다. 스칼라를 박싱 타입으로 둔 결정이 되돌려지지 않게 고정한다. */
  @Test
  @DisplayName("5. 정수·실수·불리언·문자열 스칼라가 모두 명시적 null이어도 예외 없이 null이다(박싱 타입 회귀 고정)")
  void explicitNullScalarsBindAsNull() {
    String json = """
        {"version": 1,
         "input": {"document_count": null, "doc_types": ["policy"], "ocr_confidence": {"min": null, "avg": null},
                   "pii_masked": {"total": null, "by_label": {}, "documents": null, "free_text": null}},
         "retrieval": {"policy_matched": null, "evidence": {"terms": null, "schedule": null, "case": null},
                       "disability_schedule_source": null},
         "verification": {"disability": {"extracted": null, "combined_rate": null, "confidence": null,
                                         "pending_reeval": null, "reclassified": []},
                          "judge": {"ran": null, "unavailable": null, "coverage": null, "checked_chars": null}},
         "pipeline": {"node_count": null, "total_ms": null, "failed_nodes": [], "nodes": []}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, "MEDIUM");

    assertThat(response.available()).isTrue();
    assertThat(response.input().documentCount()).isNull();
    assertThat(response.input().ocrConfidence().min()).isNull();
    assertThat(response.input().piiMasked().total()).isNull();
    assertThat(response.retrieval().policyMatched()).isNull();
    assertThat(response.retrieval().evidence().caseLaw()).isNull();
    assertThat(response.retrieval().disabilityScheduleSource()).isNull();
    assertThat(response.verification().disability().combinedRate()).isNull();
    assertThat(response.verification().disability().pendingReeval()).isNull();
    assertThat(response.verification().disability().confidence()).isNull();
    assertThat(response.verification().judge().ran()).isNull();
    assertThat(response.verification().judge().coverage()).isNull();
    assertThat(response.pipeline().nodeCount()).isNull();
    assertThat(response.pipeline().totalMs()).isNull();
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("6. 배열·맵이 없거나 null이면 빈 값, null 원소·null 값 항목은 빠진다(순서 보존)")
  void arraysAndMapsAreNormalizedToNonNull() {
    String json = """
        {"version": 1,
         "input": {"doc_types": null, "pii_masked": {"total": 3, "by_label": {"이름": 3, "전화번호": null}}},
         "verification": {"disability": {"reclassified": [null, {"injury": "골절", "from": 30.0, "to": 20.0}]},
                          "clause_citations": {"cited": 2, "ungrounded": ["제1조", null, "제2조"]}},
         "pipeline": {"node_count": 1, "nodes": [null]},
         "confidence": {"level": "LOW", "reasons": [null, "약관 근거를 찾지 못함"]}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, "low");

    assertThat(response.input().docTypes()).isEmpty();
    assertThat(response.input().piiMasked().byLabel()).containsExactly(entry("이름", 3));
    assertThat(response.verification().disability().reclassified()).extracting("injury").containsExactly("골절");
    assertThat(response.verification().clauseCitations().ungrounded()).containsExactly("제1조", "제2조");
    assertThat(response.pipeline().nodes()).isEmpty();
    assertThat(response.pipeline().failedNodes()).isEmpty();
    assertThat(response.confidence().reasons()).containsExactly("약관 근거를 찾지 못함");
  }

  @Test
  @DisplayName("6-1. 하위 객체에 배열·맵 키가 아예 없어도 빈 값이다")
  void absentArrayAndMapKeysBecomeEmpty() {
    String json = """
        {"version": 1, "input": {"document_count": 0, "pii_masked": {"total": 0}},
         "verification": {"disability": {"extracted": 0}, "clause_citations": {"cited": 0}},
         "pipeline": {"node_count": 0}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, null);

    assertThat(response.input().docTypes()).isEmpty();
    assertThat(response.input().piiMasked().byLabel()).isEmpty();
    assertThat(response.verification().disability().reclassified()).isEmpty();
    assertThat(response.verification().clauseCitations().ungrounded()).isEmpty();
    assertThat(response.pipeline().failedNodes()).isEmpty();
    assertThat(response.pipeline().nodes()).isEmpty();
  }

  @Test
  @DisplayName("7. failed_nodes는 노드명 문자열 배열 그대로 나온다(AI metrics.py 계약 고정)")
  void failedNodesAreNodeNames() {
    String json = """
        {"version": 1, "pipeline": {"node_count": 2, "failed_nodes": ["case_search"], "total_ms": 10,
                                    "nodes": [{"node": "case_search", "ms": 10, "status": "failed"}]}}
        """;

    ReportAnalysisMetricsResponse response = resolveStored(json, "MEDIUM");

    assertThat(response.pipeline().failedNodes()).containsExactly("case_search");
    assertThat(response.pipeline().nodes()).extracting("status").containsExactly("failed");
  }

  // --- 8~12: 분류 ---

  @Test
  @DisplayName("8. 초안 행이 없으면 NO_DRAFT이고 카운터는 그대로다")
  void noDraftRow() {
    given(reportDraftMetricsReader.findByReportId(reportId)).willReturn(Optional.empty());

    ReportAnalysisMetricsResponse response = resolver.resolve(reportId, "MEDIUM");

    assertUnavailable(response, "NO_DRAFT");
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("9. metrics 키가 없으면(SQL NULL) LEGACY_REPORT이고 카운터는 그대로다")
  void missingMetricsKeyIsLegacy() {
    ReportAnalysisMetricsResponse response = resolveStored(null, "MEDIUM");

    assertUnavailable(response, "LEGACY_REPORT");
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("10. metrics가 JSON null이면 LEGACY_REPORT다")
  void jsonNullMetricsIsLegacy() {
    ReportAnalysisMetricsResponse response = resolveStored("null", "MEDIUM");

    assertUnavailable(response, "LEGACY_REPORT");
    assertThat(unavailableTotal()).isZero();
  }

  @ParameterizedTest(name = "[{index}] metrics={0}")
  @ValueSource(strings = {"\"oops\"", "3", "[1]", "true", ""})
  @DisplayName("11. metrics가 객체가 아니면(문자열·숫자·배열·불리언·빈 입력) UNAVAILABLE + malformed다")
  void nonObjectMetricsIsMalformed(String metricsJson, CapturedOutput output) {
    ReportAnalysisMetricsResponse response = resolveStored(metricsJson, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("malformed")).isEqualTo(1.0);
    assertThat(unavailableTotal()).isEqualTo(1.0);
    assertThat(warnLines(output)).singleElement().asString().contains("node_type=");
  }

  @ParameterizedTest(name = "[{index}] metrics={0}")
  @ValueSource(strings = {"{\"version\":2}", "{}", "{\"version\":\"1\"}", "{\"version\":null}", "{\"version\":1.5}"})
  @DisplayName("12. version이 1이 아니거나 없거나 정수가 아니면 UNAVAILABLE + unsupported_version이다")
  void unsupportedVersionIsRejected(String metricsJson) {
    ReportAnalysisMetricsResponse response = resolveStored(metricsJson, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("unsupported_version")).isEqualTo(1.0);
    assertThat(unavailableTotal()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("12-1. version 1.0은 정수로 손실 없이 바뀌므로 v1로 해석한다")
  void versionOnePointZeroIsAccepted() {
    ReportAnalysisMetricsResponse response = resolveStored("{\"version\": 1.0}", "MEDIUM");

    assertThat(response.available()).isTrue();
    assertThat(response.version()).isEqualTo(1);
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("12-2. 거부한 version은 정수값 또는 노드 타입만 남기고 문자열 원문은 남기지 않는다")
  void unsupportedVersionLogsOnlyNumberOrNodeType(CapturedOutput output) {
    resolveStored("{\"version\": \"secret-version-text\"}", "MEDIUM");

    assertThat(warnLines(output)).singleElement().asString().contains("node_type=STRING");
    assertThat(output.getAll()).doesNotContain("secret-version-text");
  }

  @Test
  @DisplayName("13. 바인딩 실패(정수 자리에 손상명)는 malformed이고 로그에 값 없이 예외 클래스 이름만 남긴다")
  void bindingFailureLogsClassNameButNotValue(CapturedOutput output) {
    String json = """
        {"version": 1, "verification": {"disability": {"extracted": "SECRET-INJURY 추간판 탈출"}}}
        """;
    String expectedExceptionName = catchThrowable(
        () -> jsonMapper.treeToValue(jsonMapper.readTree(json), ReportAnalysisMetricsResolver.StoredMetrics.class))
        .getClass().getSimpleName();

    ReportAnalysisMetricsResponse response = resolveStored(json, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("malformed")).isEqualTo(1.0);
    assertThat(warnLines(output)).singleElement().asString().contains(expectedExceptionName);
    assertThat(output.getAll()).doesNotContain("SECRET-INJURY").doesNotContain("추간판");
  }

  // --- 14~16: 등급 출처 ---

  @Test
  @DisplayName("14. 컬럼이 null이면 level은 null이고(저장 등급을 쓰지 않는다) 사유는 저장된 값이다, 경고 없음")
  void nullColumnLevelStaysNull(CapturedOutput output) {
    ReportAnalysisMetricsResponse response = resolveStored(GOLDEN_V1, null);

    assertThat(response.available()).isTrue();
    assertThat(response.confidence().level()).isNull();
    assertThat(response.confidence().reasons()).hasSize(5);
    assertThat(warnLines(output)).isEmpty();
  }

  @Test
  @DisplayName("15. 컬럼 등급은 대문자로 정규화된다(상세와 같은 함수)")
  void columnLevelIsUpperCased() {
    ReportAnalysisMetricsResponse response = resolveStored(GOLDEN_V1, "medium");

    assertThat(response.confidence().level()).isEqualTo("MEDIUM");
  }

  @Test
  @DisplayName("16. 컬럼(LOW)과 저장 등급(MEDIUM)이 다르면 컬럼 값을 쓰고 WARN을 1회 남긴다")
  void differingLevelsUseColumnAndWarnOnce(CapturedOutput output) {
    ReportAnalysisMetricsResponse response = resolveStored(GOLDEN_V1, "LOW");

    assertThat(response.confidence().level()).isEqualTo("LOW");
    assertThat(warnLines(output)).hasSize(1);
    assertThat(unavailableTotal()).isZero();
  }

  // --- 17~20: 조회 실패·카운터 ---

  private static InvalidDataAccessResourceUsageException sqlFailure(String sqlState) {
    return new InvalidDataAccessResourceUsageException("draft read failed", new SQLException("boom", sqlState));
  }

  @Test
  @DisplayName("17. 테이블이 아직 없으면(42P01) UNAVAILABLE + table_missing이고 WARN은 남기지 않는다(DEBUG)")
  void missingTableIsTableMissingWithoutWarn(CapturedOutput output) {
    given(reportDraftMetricsReader.findByReportId(reportId)).willThrow(sqlFailure("42P01"));

    ReportAnalysisMetricsResponse response = resolver.resolve(reportId, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("table_missing")).isEqualTo(1.0);
    assertThat(unavailableTotal()).isEqualTo(1.0);
    assertThat(warnLines(output)).isEmpty();
  }

  @Test
  @DisplayName("18. 권한 없음(42501)은 query_failed이고 WARN에 SQLState·클래스 이름만 남는다(메시지 없음)")
  void permissionDeniedLogsSqlStateButNotMessage(CapturedOutput output) {
    given(reportDraftMetricsReader.findByReportId(reportId)).willThrow(sqlFailure("42501"));

    ReportAnalysisMetricsResponse response = resolver.resolve(reportId, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("query_failed")).isEqualTo(1.0);
    assertThat(warnLines(output)).singleElement().asString()
        .contains("42501")
        .contains("InvalidDataAccessResourceUsageException")
        .contains("SQLException");
    assertThat(output.getAll()).doesNotContain("boom").doesNotContain("draft read failed");
  }

  @Test
  @DisplayName("19. 리더 트랜잭션 시작(커넥션 획득) 실패도 500 없이 UNAVAILABLE + query_failed다")
  void transactionStartFailureIsQueryFailed() {
    given(reportDraftMetricsReader.findByReportId(reportId))
        .willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

    ReportAnalysisMetricsResponse response = resolver.resolve(reportId, "MEDIUM");

    assertUnavailable(response, "UNAVAILABLE");
    assertThat(unavailable("query_failed")).isEqualTo(1.0);
    assertThat(unavailableTotal()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("20. 생성 직후 원인별 카운터 4종이 0으로 등록돼 있다(한 번도 오르지 않은 원인도 대시보드에 보인다)")
  void countersArePreRegistered() {
    assertThat(meterRegistry.find(ReportAnalysisMetricsResolver.UNAVAILABLE_METRIC).counters())
        .extracting(counter -> counter.getId().getTag("cause"))
        .containsExactlyInAnyOrder("table_missing", "query_failed", "malformed", "unsupported_version");
    assertThat(unavailableTotal()).isZero();
  }

  // --- 21: degrade 범위 ---

  @Test
  @DisplayName("21. 조회 실패가 아닌 코드 결함(IllegalStateException 등)은 삼키지 않고 그대로 던진다(degrade 범위 고정)")
  void unexpectedExceptionIsNotSwallowed() {
    given(reportDraftMetricsReader.findByReportId(reportId)).willThrow(new IllegalStateException("code defect"));

    assertThatThrownBy(() -> resolver.resolve(reportId, "MEDIUM")).isInstanceOf(IllegalStateException.class);
    assertThat(unavailableTotal()).isZero();
  }

  // --- 22: 리포트 상태 게이트(resolve(Report)) ---

  private static Report report(UUID id, ReportStatus status, String confidenceLevel) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", id);
    ReflectionTestUtils.setField(report, "status", status);
    ReflectionTestUtils.setField(report, "confidenceLevel", confidenceLevel);
    return report;
  }

  @ParameterizedTest
  @EnumSource(value = ReportStatus.class, names = {"BLOCKED", "NEEDS_REUPLOAD"})
  @DisplayName("22. AI가 리포트 생성을 건너뛴 상태면 초안을 읽지 않고 NO_DRAFT다(분석 상태 판정 순서와 같다)")
  void skippedReportIsNoDraftWithoutReading(ReportStatus status) {
    ReportAnalysisMetricsResponse response = resolver.resolve(report(reportId, status, "MEDIUM"));

    assertUnavailable(response, "NO_DRAFT");
    then(reportDraftMetricsReader).shouldHaveNoInteractions();
    assertThat(unavailableTotal()).isZero();
  }

  @Test
  @DisplayName("22-1. 그 밖의 상태는 리포트의 id와 등급 컬럼으로 수치를 읽는다")
  void otherStatusReadsMetricsWithReportColumns() {
    given(reportDraftMetricsReader.findByReportId(reportId))
        .willReturn(Optional.of(ReportDraftMetricsViewFixture.of(reportId, GOLDEN_V1)));

    ReportAnalysisMetricsResponse response =
        resolver.resolve(report(reportId, ReportStatus.AWAITING_ADOPTION, "medium"));

    assertThat(response.available()).isTrue();
    assertThat(response.confidence().level()).isEqualTo("MEDIUM");
  }
}
