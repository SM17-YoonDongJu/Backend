package com.soma.backend.domain.report.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsInput;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsPipeline;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsRetrieval;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.MetricsVerification;
import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse.UnavailableReason;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportDraftMetricsView;

/**
 * AI 리포트 수치({@code ai.report_drafts.draft -> 'metrics'})를 읽어 응답으로 해석하는 조회 협력 객체(ACL).
 * 조회({@link ReportDraftMetricsReader})와 그 실패 흡수(degrade), 버전 확인, 허용 목록 바인딩, 관측을 맡는다.
 *
 * <p><b>분류(design.md §7)</b> — AI가 리포트 생성을 건너뛴 종료 상태(BLOCKED·NEEDS_REUPLOAD)와 초안 행 없음은
 * {@code NO_DRAFT}, metrics 키 없음(SQL NULL)·JSON null은 {@code LEGACY_REPORT}다. 둘 다 정상 상태라 세지 않는다.
 * 그 밖에 수치를 읽지 못하면 전부 {@code UNAVAILABLE}이고 원인을 {@code report.metrics.unavailable{cause}}
 * (Prometheus {@code report_metrics_unavailable_total})로 센다 — {@code table_missing}(SQLState 42P01, 과도기라
 * DEBUG)·{@code query_failed}(권한 없음 42501·커넥션 획득 실패 등, WARN)·{@code malformed}(객체가 아님·바인딩
 * 실패, WARN)·{@code unsupported_version}(WARN). 응답은 어느 경우든 200이다 — 이 패널 때문에 리포트 화면이 깨지면
 * 안 된다. 로그 레벨은 이 클래스가 남기는 줄 기준이다 — SQL 오류는 Hibernate({@code org.hibernate.orm.jdbc.error})가
 * 따로 WARN을 남긴다(값 없이 SQLState·DB 메시지만, 다른 {@code ai} 뷰 조회와 같은 동작).
 *
 * <p><b>버전 1만 해석한다(fail-closed).</b> AI 계약은 "키를 바꾸거나 빼면 METRICS_VERSION을 올린다"이고, 키 추가는
 * v1 그대로 두되 허용 목록과 {@code ignoreUnknown}이 흡수한다. 그러니 버전이 올랐다는 것 자체가 기존 키의 의미나
 * 모양이 바뀌었다는 신호다 — v1 규칙으로 통과시키면 같은 키의 의미가 바뀐 경우(예: 비율이 %로) 고객 화면에 틀린
 * 수치가 조용히 나간다. 닫아 두면 최악이 "패널 숨김"이고 {@code unsupported_version} 카운터로 바로 드러난다.
 * 판정은 {@code version}이 정수로 손실 없이 바뀌고 그 값이 1일 때만 통과다({@code 1.0}은 통과, {@code "1"}·누락은 거부).
 *
 * <p><b>로그에 남기는 것은 reportId·예외 클래스 이름·SQLState·JSON 노드 타입·버전 정수뿐이다.</b> 예외 메시지와
 * JSON 원문은 남기지 않는다 — Jackson 예외 메시지에는 문제가 된 토큰 값(예: 손상명 같은 건강정보)이 실리고, DB 예외
 * 메시지에는 쿼리 조각이 실린다. SQLState는 5자리 코드라 값이 실릴 수 없고 42P01·42501을 가르는 데 필요하다.
 *
 * <p><b>바깥 트랜잭션 안에서 부르지 말 것.</b> {@link ReportDraftMetricsReader}가 REQUIRES_NEW라, 호출자가 트랜잭션
 * 커넥션을 쥔 채 부르면 커넥션을 하나 더 요구한다(2026-11-11 커넥션 풀 교착과 같은 구조).
 * {@code ReviewWorkspaceQueryService}는 클래스 레벨 readOnly 트랜잭션이라 그대로 부르면 안 된다 — 파트너 검수
 * 화면은 이 수치 엔드포인트를 직접 호출하면 된다. 이 클래스에도 {@code @Transactional}을 붙이지 않는다.
 */
@Slf4j
@Component
public class ReportAnalysisMetricsResolver {

  /** 수치를 읽지 못한 횟수 카운터 이름(태그 {@code cause}). 테스트가 같은 이름으로 읽는다. */
  static final String UNAVAILABLE_METRIC = "report.metrics.unavailable";

  /** PostgreSQL {@code undefined_table}. {@code ai.report_drafts}(또는 {@code ai} 스키마)가 아직 없을 때 난다. */
  private static final String UNDEFINED_TABLE = "42P01";

  private final ReportDraftMetricsReader reportDraftMetricsReader;
  private final JsonMapper jsonMapper;
  private final Counter tableMissing;
  private final Counter queryFailed;
  private final Counter malformed;
  private final Counter unsupportedVersion;

  /** 원인별 카운터 4종을 미리 등록해, 한 번도 오르지 않은 원인도 대시보드에 0으로 보이게 한다. */
  public ReportAnalysisMetricsResolver(
      ReportDraftMetricsReader reportDraftMetricsReader, JsonMapper jsonMapper, MeterRegistry meterRegistry) {
    this.reportDraftMetricsReader = reportDraftMetricsReader;
    this.jsonMapper = jsonMapper;
    this.tableMissing = unavailableCounter(meterRegistry, "table_missing");
    this.queryFailed = unavailableCounter(meterRegistry, "query_failed");
    this.malformed = unavailableCounter(meterRegistry, "malformed");
    this.unsupportedVersion = unavailableCounter(meterRegistry, "unsupported_version");
  }

  private static Counter unavailableCounter(MeterRegistry meterRegistry, String cause) {
    return Counter.builder(UNAVAILABLE_METRIC)
        .description("AI 리포트 수치(ai.report_drafts.draft -> 'metrics')를 읽지 못해 UNAVAILABLE로 응답한 횟수"
            + "(원인별) — query_failed가 오르면 backend 롤(app_owner·app_owner_prod)의 ai.report_drafts SELECT 권한, "
            + "unsupported_version이 오르면 AI METRICS_VERSION 변경을 확인한다")
        .tag("cause", cause)
        .register(meterRegistry);
  }

  /**
   * 인가를 통과한 리포트의 수치를 응답으로 만든다. AI가 리포트 생성을 건너뛴 종료 상태(BLOCKED·NEEDS_REUPLOAD)면
   * {@code ai.report_drafts}를 읽지 않고 {@code NO_DRAFT}다 — 분석 상태 판정이 두 상태를 AI 초안 존재보다 먼저 보는
   * 것과 같은 순서다({@link Report#isReportGenerationSkipped()}). 재처리 등으로 이전 실행의 초안 행이 남아 있어도 그
   * 수치는 지금 리포트의 결과가 아니라서, 내보내면 차단·재업로드 안내 화면에 완료된 분석의 근거가 같이 뜬다.
   */
  public ReportAnalysisMetricsResponse resolve(Report report) {
    if (report.isReportGenerationSkipped()) {
      return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.NO_DRAFT);
    }
    return resolve(report.getId(), report.getConfidenceLevel());
  }

  /**
   * 리포트의 수치를 읽어 응답으로 만든다. 조회·해석에 실패해도 예외를 던지지 않고 {@code UNAVAILABLE}로
   * 낮춘다(클래스 javadoc). 단 코드 결함(NPE 등)은 잡지 않는다 — 그건 degrade가 아니라 고쳐야 할 버그다. 예외적으로
   * 바인딩 중 record 생성자에서 난 예외는 Jackson이 {@code ValueInstantiationException}(JacksonException)으로 감싸므로
   * {@code malformed}로 집계된다(WARN의 {@code binding(ValueInstantiationException)}으로 구분된다). 리포트 상태
   * 확인이 필요한 호출자는 {@link #resolve(Report)}를 쓴다.
   *
   * @param reportId        인가를 통과한 리포트 id
   * @param confidenceLevel {@code reports.confidence_level} 원값. 응답의 {@code confidence.level} 출처다
   */
  public ReportAnalysisMetricsResponse resolve(UUID reportId, @Nullable String confidenceLevel) {
    Optional<ReportDraftMetricsView> draft;
    try {
      draft = reportDraftMetricsReader.findByReportId(reportId);
    } catch (DataAccessException | TransactionException ex) {
      // 리더의 트랜잭션은 이미 롤백까지 끝났다 — 여기서 잡아도 오염될 트랜잭션이 없다(이 클래스는 트랜잭션 밖).
      onQueryFailure(reportId, ex);
      return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.UNAVAILABLE);
    }
    if (draft.isEmpty()) {
      return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.NO_DRAFT);
    }
    String metricsJson = draft.get().getMetricsJson();
    if (metricsJson == null) {
      return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.LEGACY_REPORT);
    }
    return interpret(reportId, metricsJson, confidenceLevel);
  }

  /** metrics JSON 텍스트를 트리로 먼저 읽어 분류(JSON null·비객체·버전)한 뒤에만 허용 목록 record로 바인딩한다. */
  private ReportAnalysisMetricsResponse interpret(UUID reportId, String metricsJson, @Nullable String confidenceLevel) {
    JsonNode root;
    try {
      root = jsonMapper.readTree(metricsJson);
    } catch (JacksonException ex) {
      return onMalformed(reportId, "unparsable(" + ex.getClass().getSimpleName() + ")");
    }
    // Jackson 3은 빈 입력에 null이 아니라 MissingNode를 돌려준다(databind#2211) — !isObject() 분기가 받는다.
    if (root == null) {
      return onMalformed(reportId, "no_content");
    }
    if (root.isNull()) {
      return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.LEGACY_REPORT);
    }
    if (!root.isObject()) {
      return onMalformed(reportId, "node_type=" + root.getNodeType());
    }
    JsonNode version = root.get("version");
    if (version == null || !version.canConvertToInt()
        || version.intValue() != ReportAnalysisMetricsResponse.SUPPORTED_VERSION) {
      return onUnsupportedVersion(reportId, version);
    }
    StoredMetrics stored;
    try {
      stored = jsonMapper.treeToValue(root, StoredMetrics.class);
    } catch (JacksonException ex) {
      return onMalformed(reportId, "binding(" + ex.getClass().getSimpleName() + ")");
    }
    StoredConfidence storedConfidence = stored.confidence();
    warnIfLevelDiffers(reportId, confidenceLevel, storedConfidence == null ? null : storedConfidence.level());
    return ReportAnalysisMetricsResponse.available(
        confidenceLevel,
        storedConfidence == null ? null : storedConfidence.reasons(),
        stored.input(),
        stored.retrieval(),
        stored.verification(),
        stored.pipeline());
  }

  /**
   * 등급은 컬럼 값이 이긴다(상세 게이지와 같은 값). 둘 다 있는데 다르면 등급과 사유가 서로 다른 실행에서 왔다는
   * 뜻이라(재처리 중 컬럼 쓰기만 실패 — GRANT 회수 같은 사고) 경고로 드러낸다. 컬럼이 비어 있는 것은 GRANT 전
   * 리포트의 예상 상태라 남기지 않는다.
   */
  private void warnIfLevelDiffers(UUID reportId, @Nullable String columnLevel, @Nullable String storedLevel) {
    if (columnLevel != null && storedLevel != null && !columnLevel.equalsIgnoreCase(storedLevel)) {
      log.warn("리포트 신뢰도 등급이 reports.confidence_level과 저장된 수치에서 서로 다르다 — 컬럼 값을 쓴다. "
          + "reportId={}", reportId);
    }
  }

  /** 조회 실패를 세고 남긴다. 테이블이 아직 없는 과도기만 DEBUG이고(카운터가 대신 드러낸다) 나머지는 WARN이다. */
  private void onQueryFailure(UUID reportId, NestedRuntimeException ex) {
    String sqlState = sqlStateOf(ex);
    if (UNDEFINED_TABLE.equals(sqlState)) {
      tableMissing.increment();
      log.debug("ai.report_drafts가 아직 없다 — 분석 근거 수치를 UNAVAILABLE로 응답한다. reportId={}", reportId);
      return;
    }
    queryFailed.increment();
    log.warn("ai.report_drafts 조회 실패 — 분석 근거 수치를 UNAVAILABLE로 응답한다. reportId={}, exception={}, "
        + "cause={}, sqlState={}", reportId, ex.getClass().getSimpleName(),
        ex.getMostSpecificCause().getClass().getSimpleName(), sqlState);
  }

  private ReportAnalysisMetricsResponse onMalformed(UUID reportId, String detail) {
    malformed.increment();
    log.warn("저장된 분석 근거 수치를 해석할 수 없다 — UNAVAILABLE로 응답한다. reportId={}, detail={}", reportId, detail);
    return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.UNAVAILABLE);
  }

  /** 버전 거부. 정수로 바뀌는 값만 그 값을 남기고, 그 밖에는 노드 타입만 남긴다(문자열 원문을 남기지 않는다). */
  private ReportAnalysisMetricsResponse onUnsupportedVersion(UUID reportId, @Nullable JsonNode version) {
    unsupportedVersion.increment();
    String detail;
    if (version == null) {
      detail = "missing";
    } else if (version.canConvertToInt()) {
      detail = Integer.toString(version.intValue());
    } else {
      detail = "node_type=" + version.getNodeType();
    }
    log.warn("지원하지 않는 분석 근거 수치 버전 — UNAVAILABLE로 응답한다(해석 버전 {}). reportId={}, version={}",
        ReportAnalysisMetricsResponse.SUPPORTED_VERSION, reportId, detail);
    return ReportAnalysisMetricsResponse.unavailable(UnavailableReason.UNAVAILABLE);
  }

  /** DB 오류의 SQLState. 원인 사슬 끝이 {@link SQLException}이 아니면(커넥션 획득 실패 일부 등) null이다. */
  private static @Nullable String sqlStateOf(NestedRuntimeException ex) {
    return ex.getMostSpecificCause() instanceof SQLException sqlException ? sqlException.getSQLState() : null;
  }

  /**
   * 저장된 metrics 최상위 바인딩 대상. 응답에 그대로 나가는 그룹은 응답 DTO의 중첩 record(허용 목록)를 쓰고,
   * {@code confidence}만 저장 모양({@link StoredConfidence})으로 받는다 — 응답의 level은 컬럼에서 오기 때문이다.
   * {@code version}은 바인딩 전에 트리에서 따로 확인하므로 여기 없다.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StoredMetrics(
      @Nullable MetricsInput input,
      @Nullable MetricsRetrieval retrieval,
      @Nullable MetricsVerification verification,
      @Nullable MetricsPipeline pipeline,
      @Nullable StoredConfidence confidence) {
  }

  /** 저장된 {@code confidence}. {@code level}은 컬럼과의 불일치 경고에만 쓴다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record StoredConfidence(@Nullable String level, @Nullable List<String> reasons) {
  }
}
