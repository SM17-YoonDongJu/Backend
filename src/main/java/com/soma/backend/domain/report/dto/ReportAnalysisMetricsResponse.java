package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /reports/{reportId}/analysis-metrics — AI 리포트 생성 과정의 기술 수치({@code ai.report_drafts.draft ->
 * 'metrics'}, version 1)와 신뢰도 등급의 근거(사유).
 *
 * <p><b>상세 응답에 필드를 더하지 않고 엔드포인트를 따로 둔 이유</b> — 상세({@link CustomerReportDetailResponse})는
 * 파트너 앱 draft-preview와 함께 쓰는 shape라 키우지 않는다. 패널은 지연 로딩할 수 있고, {@code ai.report_drafts}
 * 조회에 문제가 생겨도 상세 화면으로 번지지 않는다.
 *
 * <p><b>허용 목록(allowlist)이다.</b> 아래 중첩 record에 선언한 키만 내려간다 — AI가 metrics에 키를 더해도 자동으로
 * 노출되지 않는다. 필드 의미의 단일 출처는 AI 레포 {@code src/report_worker/README.md} "리포트 수치" 절이다.
 *
 * <p><b>중첩 record가 저장 JSON의 바인딩 대상을 겸한다</b>({@code ReportAnalysisMetricsResolver}가 Spring 전역
 * {@code JsonMapper}로 바인딩한다). 그래서 모든 중첩 record에 {@code @JsonIgnoreProperties(ignoreUnknown = true)}를
 * 달아 전역 설정이 바뀌어도 모르는 키가 바인딩을 깨지 않게 하고, 스칼라는 전부 박싱 타입이다 — Jackson 3 기본값
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES=true}라 원시 타입이면 명시적 null 하나로 바인딩 전체가 실패한다.
 * 배열·맵은 compact 생성자가 null을 빈 값으로 바꾸고 null 원소·값을 빼서 항상 non-null이다(프론트 zod 검증).
 *
 * <p>중첩 record 이름에 {@code Metrics} 접두를 붙인 이유는 springdoc이 중첩 record를 단순 이름으로 컴포넌트화해
 * 다른 DTO의 같은 이름(예: {@code Stats}·{@code Item})과 합쳐 버리기 때문이다. nullable 문자열에는
 * {@code allowableValues}를 달지 않는다 — swagger-core가 OpenAPI 3.1에서 {@code type}에만 "null"을 더하고
 * {@code enum}에는 넣지 않아, 엄격한 검증기에서 null 응답이 enum 위반이 된다. 값 목록은 description에 쓴다.
 */
@Schema(description = "AI 리포트 생성 과정의 기술 수치(draft.metrics v1 허용 목록)와 신뢰도 등급의 근거")
public record ReportAnalysisMetricsResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "수치를 내려줄 수 있으면 true. false면 unavailable_reason만 non-null이고 나머지는 모두 null")
    boolean available,

    @Schema(nullable = true,
        description = "available=false일 때만 non-null. "
            + "NO_DRAFT(AI 초안 없음 — 처리 중·BLOCKED·NEEDS_REUPLOAD·FAILED) "
            + "| LEGACY_REPORT(초안에 수치 없음 — AI#92 이전 리포트 또는 수치 계산 실패) "
            + "| UNAVAILABLE(수치를 읽지 못함 — 조회 실패·지원하지 않는 버전·손상된 값)")
    String unavailableReason,

    @Schema(nullable = true, description = "수치 스키마 버전. available=true면 항상 1, false면 null")
    Integer version,

    @Schema(nullable = true, description = "신뢰도 등급과 사유. available=true면 항상 non-null, false면 null")
    MetricsConfidence confidence,

    @Schema(nullable = true, description = "입력 품질. " + GROUP_NULL_CONDITION)
    MetricsInput input,

    @Schema(nullable = true, description = "검색 근거. " + GROUP_NULL_CONDITION)
    MetricsRetrieval retrieval,

    @Schema(nullable = true, description = "검증 결과. " + GROUP_NULL_CONDITION)
    MetricsVerification verification,

    @Schema(nullable = true, description = "처리 경로. " + GROUP_NULL_CONDITION)
    MetricsPipeline pipeline) {

  /** 이 응답이 해석하는 {@code metrics.version}. 다른 버전은 해석하지 않는다(fail-closed, Resolver javadoc). */
  public static final int SUPPORTED_VERSION = 1;

  /** 최상위 그룹 4종 공통 null 조건. */
  private static final String GROUP_NULL_CONDITION =
      "available=false면 null. true여도 저장된 수치에 이 그룹이 없으면 null";

  /** 스칼라·하위 객체를 가진 중첩 record 공통 설명(필드별 의미상 null 조건은 각 필드 description에 쓴다). */
  private static final String MISSING_AS_NULL =
      "v1에서 항상 값이 있는 필드도 저장된 수치에서 빠지거나 null이면 null로 내려간다. 배열·맵은 null 대신 빈 값이다.";

  /** {@code available=false}의 사유. 응답 필드는 {@code name()} 문자열이다(스키마에 enum으로 노출하지 않는다). */
  public enum UnavailableReason {
    /**
     * AI 초안이 없다 — 처리 중·BLOCKED·NEEDS_REUPLOAD·FAILED(실패한 리포트는 초안을 저장하지 않는다).
     * BLOCKED·NEEDS_REUPLOAD는 이전 실행의 초안 행이 남아 있어도 이 값이다(분석 상태 판정 순서와 같다).
     */
    NO_DRAFT,
    /** 초안에 수치가 없다 — AI#92 이전 리포트이거나 수치 계산이 실패했다(데이터로는 둘을 구분할 수 없다). */
    LEGACY_REPORT,
    /** 수치를 읽지 못했다 — 조회 실패·지원하지 않는 버전·손상된 값(원인은 카운터 태그로 구분). */
    UNAVAILABLE
  }

  /** 수치를 내려줄 수 없는 응답. {@code unavailable_reason} 외 필드는 전부 null이다. */
  public static ReportAnalysisMetricsResponse unavailable(UnavailableReason reason) {
    return new ReportAnalysisMetricsResponse(false, reason.name(), null, null, null, null, null, null);
  }

  /**
   * 수치 응답. {@code confidence.level}은 저장된 수치가 아니라 {@code reports.confidence_level}을 상세와 같은
   * 함수로 정규화한 값이다(상세 게이지와 패널 등급이 항상 같다). {@code reasons}만 저장된 수치에서 온다.
   *
   * @param confidenceLevelColumn {@code reports.confidence_level} 원값(정규화 전). null이면 level도 null이다
   * @param reasons               저장된 수치의 {@code confidence.reasons}. null이면 빈 배열이 된다
   */
  public static ReportAnalysisMetricsResponse available(
      @Nullable String confidenceLevelColumn, @Nullable List<String> reasons, @Nullable MetricsInput input,
      @Nullable MetricsRetrieval retrieval, @Nullable MetricsVerification verification,
      @Nullable MetricsPipeline pipeline) {
    MetricsConfidence confidence =
        new MetricsConfidence(ReportResponseSupport.confidenceLevel(confidenceLevelColumn), reasons);
    return new ReportAnalysisMetricsResponse(
        true, null, SUPPORTED_VERSION, confidence, input, retrieval, verification, pipeline);
  }

  /** 신뢰도 등급과 사유. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "신뢰도 등급(reports.confidence_level)과 그 사유(저장된 수치). HIGH면 사유가 없는 것이 정상이다")
  public record MetricsConfidence(
      @Schema(nullable = true,
          description = "reports.confidence_level(상세 confidence_level과 같은 값, 대문자). 비어 있으면 null. "
              + "관례값 HIGH | MEDIUM | LOW")
      String level,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
          description = "등급 사유(화면에 그대로 써도 되는 고정 문구, LOW 사유가 먼저). 없으면 빈 배열")
      List<String> reasons) {

    /** 사유 목록을 non-null로 정규화한다(null 원소 제거, 순서 보존). */
    public MetricsConfidence {
      reasons = ReportResponseSupport.nonNullElements(reasons);
    }
  }

  /** 입력 품질. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "입력 품질. " + MISSING_AS_NULL)
  public record MetricsInput(
      @Schema(nullable = true, description = "입력 문서 수")
      Integer documentCount,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
          description = "문서 순 doc_type(중복 포함, 예: policy·diagnosis·medical_receipt). 없으면 빈 배열")
      List<String> docTypes,

      @Schema(nullable = true, description = "OCR 신뢰도. 유효한 값이 없으면 null")
      MetricsScoreStats ocrConfidence,

      @Schema(nullable = true, description = "문서 유형 분류 신뢰도. 유효한 값이 없으면 null")
      MetricsScoreStats docTypeConfidence,

      @Schema(nullable = true, description = "개인정보 마스킹 건수")
      MetricsPiiMasked piiMasked) {

    /** 문서 유형 목록을 non-null로 정규화한다(null 원소 제거, 순서 보존). */
    public MetricsInput {
      docTypes = ReportResponseSupport.nonNullElements(docTypes);
    }
  }

  /** 신뢰도 점수 요약(최솟값·평균). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "0~1, 소수 3자리. 0.0은 인식된 라인이 없던 문서. " + MISSING_AS_NULL)
  public record MetricsScoreStats(
      @Schema(nullable = true, description = "최솟값")
      Double min,

      @Schema(nullable = true, description = "평균")
      Double avg) {
  }

  /** 개인정보 마스킹 건수. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "마스킹 토큰([분류]) 수. " + MISSING_AS_NULL)
  public record MetricsPiiMasked(
      @Schema(nullable = true, description = "전체")
      Integer total,

      /*
       * implementation = Object.class는 문서 생성 우회다(런타임 직렬화·바인딩은 Map<String, Integer> 그대로).
       * OpenApiConfig가 swagger-core 정적 매퍼에 SNAKE_CASE를 걸어 둬서, swagger-core가 프로퍼티 스키마를 복제
       * (직렬화→역직렬화)할 때 Map이 만드는 additionalProperties가 additional_properties로 바뀌어 역직렬화에
       * 실패하고, 이 응답 스키마 전체가 문서에서 빠진다. Object로 해석시키면 복제 단계에 additionalProperties가
       * 없고, 값 타입은 복제 뒤에 적용되는 additionalPropertiesSchema로 문서화된다.
       */
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = "object", implementation = Object.class,
          additionalPropertiesSchema = Integer.class,
          description = "한국어 라벨별 건수(라벨 → 정수, 예: 이름·전화번호). 키 순서에 의미 없음(jsonb 정렬). "
              + "없으면 빈 객체")
      Map<String, Integer> byLabel,

      @Schema(nullable = true, description = "OCR 문서 본문의 토큰 수")
      Integer documents,

      @Schema(nullable = true, description = "사용자 자유입력(질문·설명·추가정보)의 토큰 수")
      Integer freeText) {

    /** 라벨별 건수를 non-null로 정규화한다(키·값이 null인 항목 제거). */
    public MetricsPiiMasked {
      byLabel = ReportResponseSupport.nonNullEntries(byLabel);
    }
  }

  /** 검색 근거. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "검색 근거. " + MISSING_AS_NULL)
  public record MetricsRetrieval(
      @Schema(nullable = true, description = "가입 상품 약관을 DB에서 찾았으면 true")
      Boolean policyMatched,

      @Schema(nullable = true, description = "Judge 근거 청크 수(중복 제거 후)")
      MetricsEvidence evidence,

      @Schema(nullable = true, description = "판례 검색 깔때기. 판례 검색 단계가 실패하면 null")
      MetricsCaseFunnel caseFunnel,

      @Schema(nullable = true,
          description = "장해분류표 출처. terms(가입 약관) | standard(표준표 폴백). "
              + "후유장해 경로가 아니거나 표를 검색하지 않았으면 null")
      String disabilityScheduleSource) {
  }

  /** Judge 근거 청크 수(출처별). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "Judge 근거 청크 수(출처별, 중복 제거 후). " + MISSING_AS_NULL)
  public record MetricsEvidence(
      @Schema(nullable = true, description = "약관")
      Integer terms,

      @Schema(nullable = true, description = "장해분류표")
      Integer schedule,

      /* 'case'는 Java 예약어라 컴포넌트 이름을 바꾸고 JSON 키만 명시한다(네이밍 전략은 명시 이름을 바꾸지 않는다). */
      @JsonProperty("case")
      @Schema(nullable = true, description = "판례")
      Integer caseLaw) {
  }

  /** 판례 검색 깔때기. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "판례 검색 깔때기 — 후보 → 사건 단위 중복 제거 → 관련성 필터 → 상위 4. 결과가 0건이면 "
      + "전부 0. 필터가 판정에 실패하거나 전부 탈락시키면 그대로 통과시키므로, relevant=deduped라도 "
      + "'걸러낼 것이 없었다'는 뜻은 아니다. " + MISSING_AS_NULL)
  public record MetricsCaseFunnel(
      @Schema(nullable = true, description = "검색 후보 수")
      Integer candidates,

      @Schema(nullable = true, description = "사건 단위 중복 제거 후")
      Integer deduped,

      @Schema(nullable = true, description = "관련성 필터 통과")
      Integer relevant,

      @Schema(nullable = true, description = "최종 채택(상위 4)")
      Integer kept) {
  }

  /** 검증 결과. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "검증 결과. " + MISSING_AS_NULL)
  public record MetricsVerification(
      @Schema(nullable = true, description = "후유장해 검증. 후유장해 경로가 아니면 null")
      MetricsDisability disability,

      @Schema(nullable = true, description = "본문 조항 인용 실재율(정규식 휴리스틱)")
      MetricsClauseCitations clauseCitations,

      @Schema(nullable = true, description = "인용 검증(Judge) 결과")
      MetricsJudge judge) {
  }

  /** 후유장해 검증. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "후유장해 검증. " + MISSING_AS_NULL)
  public record MetricsDisability(
      @Schema(nullable = true, description = "추출한 장해 항목 수")
      Integer extracted,

      @Schema(nullable = true, description = "검증한 항목 수")
      Integer verified,

      @Schema(nullable = true, description = "합산 지급률(%)")
      Double combinedRate,

      @Schema(nullable = true, description = "장해 감수 신뢰도 원문(소문자, 예: high·medium·low)")
      String confidence,

      @Schema(nullable = true, description = "재평가 대상 여부")
      Boolean pendingReeval,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "과대분류로 낮춘 항목. 없으면 빈 배열")
      List<MetricsReclassification> reclassified) {

    /** 하향 교정 목록을 non-null로 정규화한다(null 원소 제거, 순서 보존). */
    public MetricsDisability {
      reclassified = ReportResponseSupport.nonNullElements(reclassified);
    }
  }

  /** 과대분류로 낮춘 장해 항목 1건. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "과대분류로 낮춘 장해 항목. " + MISSING_AS_NULL)
  public record MetricsReclassification(
      @Schema(nullable = true, description = "손상명(건강정보)")
      String injury,

      @Schema(nullable = true, description = "원래 지급률(%)")
      Double from,

      @Schema(nullable = true, description = "낮춘 지급률(%)")
      Double to) {
  }

  /** 본문 조항 인용 실재율. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "본문 조항 인용 실재율(정규식 휴리스틱). " + MISSING_AS_NULL)
  public record MetricsClauseCitations(
      @Schema(nullable = true, description = "본문이 인용한 약관 조항 수(중복 제거, 법령 조문 제외)")
      Integer cited,

      @Schema(nullable = true, description = "그중 근거 청크에 있는 조항 수")
      Integer grounded,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
          description = "근거에 없는 조항 번호(본문 첫 등장 순, 예: 제99조). 없으면 빈 배열")
      List<String> ungrounded,

      @Schema(nullable = true, description = "법령 조문 인용 수(cited에 포함되지 않음)")
      Integer statuteMentions) {

    /** 근거 없는 조항 목록을 non-null로 정규화한다(null 원소 제거, 순서 보존). */
    public MetricsClauseCitations {
      ungrounded = ReportResponseSupport.nonNullElements(ungrounded);
    }
  }

  /** 인용 검증(Judge) 결과. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "인용 검증(Judge) 결과. " + MISSING_AS_NULL)
  public record MetricsJudge(
      @Schema(nullable = true, description = "판정을 수행했으면 true(checked_chars > 0)")
      Boolean ran,

      @Schema(nullable = true, description = "Judge를 쓸 수 없었으면 true")
      Boolean unavailable,

      @Schema(nullable = true, description = "근거 미확인 판정 수")
      Integer flagged,

      @Schema(nullable = true, description = "검증한 본문 글자 수")
      Integer checkedChars,

      @Schema(nullable = true, description = "본문 전체 글자 수")
      Integer totalChars,

      @Schema(nullable = true, description = "checked_chars/total_chars(소수 2자리). total_chars가 0이면 0.0")
      Double coverage,

      @Schema(nullable = true, description = "Judge에 넘긴 근거 청크 수(상한 20)")
      Integer evidenceChunks) {
  }

  /** 처리 경로. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "output_guardrail까지의 노드 실행 기록(collect_metrics·persist 제외). " + MISSING_AS_NULL)
  public record MetricsPipeline(
      @Schema(nullable = true, description = "노드 수")
      Integer nodeCount,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "실패한 노드명. 없으면 빈 배열")
      List<String> failedNodes,

      @Schema(nullable = true, description = "노드 소요 ms의 합(벽시계 시간이 아님)")
      Integer totalMs,

      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "노드별 기록(실행 순). 없으면 빈 배열")
      List<MetricsPipelineNode> nodes) {

    /** 실패 노드·노드 기록 목록을 non-null로 정규화한다(null 원소 제거, 순서 보존). */
    public MetricsPipeline {
      failedNodes = ReportResponseSupport.nonNullElements(failedNodes);
      nodes = ReportResponseSupport.nonNullElements(nodes);
    }
  }

  /** 노드 1건의 실행 기록. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @Schema(description = "노드 실행 기록. " + MISSING_AS_NULL)
  public record MetricsPipelineNode(
      @Schema(nullable = true, description = "노드명")
      String node,

      @Schema(nullable = true, description = "소요 ms")
      Integer ms,

      @Schema(nullable = true, description = "ok | failed")
      String status) {
  }
}
