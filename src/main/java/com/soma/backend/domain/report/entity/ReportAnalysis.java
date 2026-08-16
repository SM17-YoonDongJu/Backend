package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * 리포트 분석 처리 상태 판정 결과 VO(불변). 식별자가 없고 DB에 저장하지 않는 파생값이라 record로 둔다.
 *
 * <p><b>판정 우선순위(불변식)</b> — 규칙이지 조율이 아니므로 서비스의 {@code if} 체인이 아니라 이 VO가 소유한다
 * (design.md §4-1).
 *
 * <ol>
 *   <li>{@code report.status == BLOCKED} → {@link AnalysisState#BLOCKED} — AI 입력 가드레일이 OCR·초안
 *       생성 이전에 파이프라인을 끊어버려 저널에 흔적이 안 남는다. <b>가장 먼저, 저널 조회보다도 앞서</b>
 *       확인한다 — 이 신호가 존재하면 애초에 AI 초안이 생성될 수 없으므로 다른 신호와 충돌할 일이 없다.</li>
 *   <li>{@code report.status == NEEDS_REUPLOAD} → {@link AnalysisState#NEEDS_REUPLOAD} — OCR 품질 미달로
 *       AI가 리포트 생성을 건너뛴 신호. 실패가 아니라 품질 판정이라 저널에 흔적이 없다. <b>AI 초안 검사보다
 *       앞</b>이며 이 순서가 설계의 핵심이다(아래 참고).</li>
 *   <li>AI 초안 생성됨 → {@link AnalysisState#COMPLETED} — <b>실패 행이 남아 있어도 성공이 이긴다</b>.
 *       AI 워커의 실패 행 삭제({@code clear_job_failure})가 실패해도 사용자에게 실패로 보이지 않게 한다(§8 E3).</li>
 *   <li>확정 실패(terminal=true) 존재 → {@link AnalysisState#FAILED}</li>
 *   <li>그 외 → {@link AnalysisState#PROCESSING} — 일시 실패(terminal=false) 행은 애초에 조회되지 않아
 *       여기에 포함된다(§8 E1)</li>
 * </ol>
 *
 * <p><b>NEEDS_REUPLOAD가 AI 초안·저널보다 앞인 이유(두 가지):</b> ① 초안이 있는데 품질 미달 판정이 늦게
 * 쓰인 모순 상태에서 COMPLETED를 내리면 "완료로 보이는데 실제로는 멈춰 있는" 무음 정지가 그대로 재현된다 —
 * 상태 컬럼은 AI가 명시적으로 쓴 최신 신호이므로 이쪽을 신뢰한다. ② 저널 검사(4번)보다 앞이라
 * {@link #isFailed()}가 {@code false}가 되어, 저널 기반 스윕
 * ({@code AnalysisFailureNotificationSweeper})과의 <b>중복 알림이 구조적으로 차단</b>된다 — 품질 판정
 * 리포트에 저널 행이 같이 생기더라도 안전하다.</p>
 *
 * <p>판정은 문서 단위가 아니라 <b>리포트 단위</b>다 — AI측 fan-in(doc_total)이 다 차지 않으면 리포트 생성이
 * 시작되지 않으므로 "문서 일부만 성공"은 사용자에게 의미가 없다(§8 E6).
 *
 * @param state     파생 상태
 * @param reason    대표 실패 사유. {@code state != FAILED}면 null
 * @param failedAt  대표 실패 시각(실패 행들의 {@code first_failed_at} 최솟값). {@code state != FAILED}면 null
 * @param documents 실패 문서 목록. FAILED가 아니면 빈 리스트(null 아님)
 */
public record ReportAnalysis(
    AnalysisState state,
    @Nullable AnalysisFailureReason reason,
    @Nullable LocalDateTime failedAt,
    List<FailedDocument> documents) {

  private static final ReportAnalysis PROCESSING =
      new ReportAnalysis(AnalysisState.PROCESSING, null, null, List.of());
  private static final ReportAnalysis COMPLETED =
      new ReportAnalysis(AnalysisState.COMPLETED, null, null, List.of());
  private static final ReportAnalysis BLOCKED =
      new ReportAnalysis(AnalysisState.BLOCKED, null, null, List.of());
  private static final ReportAnalysis NEEDS_REUPLOAD =
      new ReportAnalysis(AnalysisState.NEEDS_REUPLOAD, null, null, List.of());

  /** 사용자 노출 문구(BLOCKED 전용). 내부 사유(오프토픽·PII 복호화 실패 등)를 구분하지 않고 중립적으로 서술한다 —
   *  AI가 그 사유를 reports 테이블로 넘겨주지 않아 Backend가 구분할 수단이 없다. */
  private static final String BLOCKED_MESSAGE = "처리할 수 없는 요청이에요. 고객센터로 문의해주세요.";

  /** 사용자 노출 문구(NEEDS_REUPLOAD 전용). 사용자 과실을 단정하지 않고("문서가 잘못됐다" 금지) 처리 상태와
   *  다음 행동만 중립적으로 서술한다. 조회 API 응답과 푸시 본문이 이 상수 하나를 공유한다(단일 진실). */
  private static final String NEEDS_REUPLOAD_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";

  public ReportAnalysis {
    documents = documents == null ? List.of() : List.copyOf(documents);
  }

  /**
   * 실패 저널(terminal=true 행)과 리포트의 AI 초안 존재 여부로 상태를 판정한다.
   *
   * @param report           대상 리포트(성공·차단 신호 판정용)
   * @param terminalFailures 해당 리포트의 확정 실패 행. 호출자는 반드시 terminal=true만 넘긴다
   */
  public static ReportAnalysis of(Report report, List<OcrJobFailureView> terminalFailures) {
    if (report != null && report.getStatus() == ReportStatus.BLOCKED) {
      return BLOCKED;
    }
    // AI 초안 검사보다 앞이다(클래스 javadoc 참고): 초안이 있어도 품질 미달 판정이 이기고, 저널 검사보다
    // 앞이라 isFailed()가 false가 되어 저널 기반 스윕과의 중복 알림이 구조적으로 차단된다.
    if (report != null && report.getStatus() == ReportStatus.NEEDS_REUPLOAD) {
      // 청구 fan-in으로 걸린 NEEDS_REUPLOAD(#67)는 개별 문서 품질 게이트와 달리 ai.ocr_job_failures에
      // 흔적이 남는다 — 이미 GRANT된 저널이라 있으면 문서를 특정해 보여준다(§8 E9와 동일하게 attachment_id
      // 없는 행은 null). 대표 사유·failed_at은 여전히 null이다(§4-2 — 문서별 사유는 있어도 리포트 단위
      // 대표 사유·시각 개념은 이 상태에 없다). 개별 문서 품질 게이트로 걸린 경우는 저널에 흔적이 없어(A1)
      // 이전처럼 빈 배열이다.
      return terminalFailures == null || terminalFailures.isEmpty()
          ? NEEDS_REUPLOAD
          : new ReportAnalysis(AnalysisState.NEEDS_REUPLOAD, null, null, toFailedDocuments(terminalFailures));
    }
    if (report != null && report.isAiDraftGenerated()) {
      return COMPLETED;
    }
    if (terminalFailures == null || terminalFailures.isEmpty()) {
      return PROCESSING;
    }
    List<FailedDocument> documents = toFailedDocuments(terminalFailures);
    AnalysisFailureReason representative =
        AnalysisFailureReason.representative(documents.stream().map(FailedDocument::reason).toList());
    LocalDateTime failedAt = terminalFailures.stream()
        .map(OcrJobFailureView::getFirstFailedAt)
        .filter(Objects::nonNull)
        .min(Comparator.naturalOrder())
        .orElse(null);
    return new ReportAnalysis(AnalysisState.FAILED, representative, failedAt, documents);
  }

  private static List<FailedDocument> toFailedDocuments(List<OcrJobFailureView> terminalFailures) {
    return terminalFailures.stream()
        .map(failure -> new FailedDocument(failure.getAttachmentId(), failure.reason()))
        .toList();
  }

  /** 분석 진행 중(기본값). ai 저널 조회가 불가능할 때의 degrade 값이기도 하다(§8 E14). */
  public static ReportAnalysis processing() {
    return PROCESSING;
  }

  /** AI 초안 생성 완료. */
  public static ReportAnalysis completed() {
    return COMPLETED;
  }

  /** AI 입력 가드레일 차단({@code reports.status == BLOCKED}). */
  public static ReportAnalysis blocked() {
    return BLOCKED;
  }

  /** OCR 품질 미달로 재업로드가 필요함({@code reports.status == NEEDS_REUPLOAD}). */
  public static ReportAnalysis needsReupload() {
    return NEEDS_REUPLOAD;
  }

  public boolean isFailed() {
    return state == AnalysisState.FAILED;
  }

  /** 사용자 노출 문구. FAILED·BLOCKED·NEEDS_REUPLOAD가 아니면 null. */
  public @Nullable String failureMessage() {
    if (state == AnalysisState.BLOCKED) {
      return BLOCKED_MESSAGE;
    }
    if (state == AnalysisState.NEEDS_REUPLOAD) {
      return NEEDS_REUPLOAD_MESSAGE;
    }
    return reason == null ? null : reason.getUserMessage();
  }

  /**
   * 재업로드 안내. FAILED·BLOCKED·NEEDS_REUPLOAD가 아니면 null. BLOCKED는 문서 문제가 아니라 재업로드가
   * 무의미해 NOT_SUPPORTED고, NEEDS_REUPLOAD는 재업로드가 유일한 해결책이라 항상 RECOMMENDED다.
   */
  public @Nullable ReuploadGuidance reuploadGuidance() {
    if (state == AnalysisState.BLOCKED) {
      return ReuploadGuidance.NOT_SUPPORTED;
    }
    if (state == AnalysisState.NEEDS_REUPLOAD) {
      return ReuploadGuidance.RECOMMENDED;
    }
    return reason == null ? null : reason.getReuploadGuidance();
  }

  /**
   * 실패 문서 1건.
   *
   * @param attachmentId {@code report_attachments.id}. 저널에 없으면 null(§8 E9)
   * @param reason       문서별 실패 사유
   */
  public record FailedDocument(@Nullable UUID attachmentId, AnalysisFailureReason reason) {
  }
}
