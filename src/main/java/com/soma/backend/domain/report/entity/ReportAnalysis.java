package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * 리포트 분석 처리 상태 판정 결과 VO(불변). 식별자가 없고 DB에 저장하지 않는 파생값이라 record로 둔다.
 *
 * <p><b>판정 우선순위(불변식)</b> — 규칙이지 조율이 아니므로 서비스의 {@code if} 체인이 아니라 이 VO가 소유한다
 * (design.md §4-1).
 *
 * <ol>
 *   <li>{@code report.status == BLOCKED} → {@link AnalysisState#BLOCKED} — AI 입력 가드레일이 OCR 이전에
 *       파이프라인을 끊었거나, 리포트 생성 LLM이 응답을 거절했다(서버 측 폴백 모델까지 거절). 어느 쪽도 저널에
 *       흔적을 남기지 않는다. <b>가장 먼저, 저널 조회보다도 앞서</b> 확인한다 — 이 신호가 존재하면 애초에 AI
 *       초안이 생성될 수 없으므로 다른 신호와 충돌할 일이 없다.</li>
 *   <li>{@code report.status == NEEDS_REUPLOAD} → {@link AnalysisState#NEEDS_REUPLOAD} — OCR 품질 미달로
 *       AI가 리포트 생성을 건너뛴 신호. 실패가 아니라 품질 판정이라 저널에 흔적이 없다. <b>AI 초안 검사보다
 *       앞</b>이며 이 순서가 설계의 핵심이다(아래 참고).</li>
 *   <li>AI 초안 생성됨 → {@link AnalysisState#COMPLETED} — <b>실패 행이 남아 있어도 성공이 이긴다</b>.
 *       AI 워커의 실패 행 삭제가 실패하거나, 경합으로 성공한 리포트에 terminal 행이 남아도 사용자에게 실패로
 *       보이지 않게 한다(§8 E3).</li>
 *   <li>OCR 확정 실패({@code ai.ocr_job_failures} terminal) 존재 → {@link AnalysisState#FAILED} — 실패 문서와
 *       문서별 사유를 특정한다.</li>
 *   <li>리포트 생성 확정 실패({@code ai.report_job_failures} terminal) 존재 → {@link AnalysisState#FAILED} —
 *       재전달 한도를 다 써 워커가 포기한 리포트다. 리포트 단위 실패라 특정 문서가 없다.</li>
 *   <li>그 외 → {@link AnalysisState#PROCESSING} — 일시 실패(terminal=false) 행은 애초에 조회되지 않아
 *       여기에 포함된다(§8 E1)</li>
 * </ol>
 *
 * <p>4·5번 순서는 AI 레포 contracts.md §4-1이 정한 계약이다. 두 저널에 모두 행이 있으면 문서와 사유를 특정하는
 * OCR 쪽 FAILED가 이긴다.
 *
 * <p><b>NEEDS_REUPLOAD가 AI 초안·저널보다 앞인 이유(두 가지):</b> ① 초안이 있는데 품질 미달 판정이 늦게
 * 쓰인 모순 상태에서 COMPLETED를 내리면 "완료로 보이는데 실제로는 멈춰 있는" 무음 정지가 그대로 재현된다 —
 * 상태 컬럼은 AI가 명시적으로 쓴 최신 신호이므로 이쪽을 신뢰한다. ② 저널 검사(4·5번)보다 앞이라
 * {@link #isFailed()}가 {@code false}가 되어, 저널 기반 스윕
 * ({@code ReportAnalysisFailureNotificationSweeper})과의 <b>중복 알림이 구조적으로 차단</b>된다 — 품질 판정
 * 리포트에 저널 행이 같이 생기더라도 안전하다.</p>
 *
 * <p><b>FAILED도 굳은 상태가 아니다.</b> 워커가 회복하면 저널 행을 지운다 — 리포트 생성 저널은 같은 리포트의
 * 다른 메시지가 성공해도 terminal 행을 지운다. 그래서 이 판정은 저장·캐시하지 않고 조회할 때마다 다시 하며,
 * FAILED 알림을 받은 리포트가 나중에 COMPLETED로 바뀌는 것도 정상 경로다.
 *
 * <p>판정은 문서 단위가 아니라 <b>리포트 단위</b>다 — AI측 fan-in(doc_total)이 다 차지 않으면 리포트 생성이
 * 시작되지 않으므로 "문서 일부만 성공"은 사용자에게 의미가 없다(§8 E6).
 *
 * @param state     파생 상태
 * @param reason    대표 실패 사유. {@code state != FAILED}면 null
 * @param failedAt  대표 실패 시각(실패 행들의 {@code first_failed_at} 최솟값). {@code state != FAILED}면 null
 * @param documents 실패 문서 목록. FAILED가 아니면 빈 리스트(null 아님). 리포트 생성 단계 FAILED(5번)도 특정
 *                  문서가 없어 빈 리스트다
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

  /** 사용자 노출 문구(BLOCKED 전용). 내부 사유(오프토픽·PII 복호화 실패·LLM 거절 등)를 구분하지 않고 중립적으로
   *  서술한다 — AI가 그 사유를 reports 테이블로 넘겨주지 않아 Backend가 구분할 수단이 없다. */
  private static final String BLOCKED_MESSAGE = "처리할 수 없는 요청이에요. 고객센터로 문의해주세요.";

  /** 사용자 노출 문구(NEEDS_REUPLOAD 전용). 사용자 과실을 단정하지 않고("문서가 잘못됐다" 금지) 처리 상태와
   *  다음 행동만 중립적으로 서술한다. 조회 API 응답과 푸시 본문이 이 상수 하나를 공유한다(단일 진실). */
  private static final String NEEDS_REUPLOAD_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";

  public ReportAnalysis {
    documents = documents == null ? List.of() : List.copyOf(documents);
  }

  /**
   * {@link #of(Report, List, List, List)}에 품질 미달 문서 없이 위임한다 — 문서 특정이 필요 없는 호출자용이다
   * (예: 알림 스윕은 상태와 대표 사유만 쓴다). 두 실패 저널은 상태 자체를 바꾸는 입력이라 생략할 수 없다.
   */
  public static ReportAnalysis of(
      Report report, List<OcrJobFailureView> ocrJobFailures, List<ReportJobFailureView> reportJobFailures) {
    return of(report, ocrJobFailures, reportJobFailures, List.of());
  }

  /**
   * 두 실패 저널(terminal=true 행)·품질 미달 문서·리포트의 AI 초안 존재 여부로 상태를 판정한다.
   *
   * @param report                 대상 리포트(성공·차단 신호 판정용)
   * @param ocrJobFailures         해당 리포트의 OCR 확정 실패 행({@code ai.ocr_job_failures}). 호출자는 반드시
   *                               terminal=true만 넘긴다
   * @param reportJobFailures      해당 리포트의 리포트 생성 확정 실패 행({@code ai.report_job_failures}). 뷰가
   *                               terminal 행만 노출한다. 012 미적용 등으로 못 읽으면 호출자가 빈 리스트로 degrade한다
   * @param needsReuploadDocuments 해당 리포트의 품질 미달 문서({@code ai.ocr_results}, {@code ocr_quality
   *                               = 'needs_reupload'}). GRANT 미배포 상태면 호출자가 빈 리스트로 degrade한다
   */
  public static ReportAnalysis of(
      Report report, List<OcrJobFailureView> ocrJobFailures, List<ReportJobFailureView> reportJobFailures,
      List<OcrResultView> needsReuploadDocuments) {
    if (report != null && report.getStatus() == ReportStatus.BLOCKED) {
      return BLOCKED;
    }
    // AI 초안 검사보다 앞이다(클래스 javadoc 참고): 초안이 있어도 품질 미달 판정이 이기고, 저널 검사보다
    // 앞이라 isFailed()가 false가 되어 저널 기반 스윕과의 중복 알림이 구조적으로 차단된다.
    if (report != null && report.getStatus() == ReportStatus.NEEDS_REUPLOAD) {
      // 두 계약이 서로 다른 문서 집합을 특정할 수 있다 — 청구 fan-in(#67)은 ai.ocr_job_failures에 흔적이
      // 남고, 개별 문서 품질 게이트는 ai.ocr_results.ocr_quality='needs_reupload'로 특정된다(둘 다 없으면
      // A1대로 개별 게이트가 원인). 대표 사유·failed_at은 여전히 null이다(§4-2 — 문서별 사유는 있어도
      // 리포트 단위 대표 사유·시각 개념은 이 상태에 없다).
      List<FailedDocument> documents = mergeFailedDocuments(ocrJobFailures, needsReuploadDocuments);
      return documents.isEmpty()
          ? NEEDS_REUPLOAD
          : new ReportAnalysis(AnalysisState.NEEDS_REUPLOAD, null, null, documents);
    }
    if (report != null && report.isAiDraftGenerated()) {
      return COMPLETED;
    }
    if (ocrJobFailures != null && !ocrJobFailures.isEmpty()) {
      return ocrJobFailed(ocrJobFailures);
    }
    if (reportJobFailures != null && !reportJobFailures.isEmpty()) {
      return reportJobFailed(reportJobFailures);
    }
    return PROCESSING;
  }

  /** OCR 확정 실패(4번) — 실패 문서별 사유를 모아 대표 사유를 고른다(§8 E5). */
  private static ReportAnalysis ocrJobFailed(List<OcrJobFailureView> ocrJobFailures) {
    List<FailedDocument> documents = toFailedDocuments(ocrJobFailures);
    AnalysisFailureReason representative =
        AnalysisFailureReason.representative(documents.stream().map(FailedDocument::reason).toList());
    LocalDateTime failedAt = earliest(ocrJobFailures.stream().map(OcrJobFailureView::getFirstFailedAt));
    return new ReportAnalysis(AnalysisState.FAILED, representative, failedAt, documents);
  }

  /** 리포트 생성 확정 실패(5번) — 리포트 단위 실패라 실패 문서 목록은 비어 있다. */
  private static ReportAnalysis reportJobFailed(List<ReportJobFailureView> reportJobFailures) {
    AnalysisFailureReason representative = AnalysisFailureReason.representative(
        reportJobFailures.stream().map(ReportJobFailureView::reason).toList());
    LocalDateTime failedAt = earliest(reportJobFailures.stream().map(ReportJobFailureView::getFirstFailedAt));
    return new ReportAnalysis(AnalysisState.FAILED, representative, failedAt, List.of());
  }

  /** 대표 실패 시각 — 가장 이른 {@code first_failed_at}. NULL은 건너뛰고, 남는 값이 없으면 null이다. */
  private static @Nullable LocalDateTime earliest(Stream<LocalDateTime> timestamps) {
    return timestamps.filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
  }

  /**
   * NEEDS_REUPLOAD 문서 목록 — 청구 fan-in으로 OCR 저널에 남은 문서(사유 있음)와 개별 품질 게이트로 걸린 문서
   * (사유 없음)를 합친다. 리포트 생성 저널은 특정 문서가 없어 여기에 들어오지 않는다. 입력이 null이면 건너뛴다.
   */
  private static List<FailedDocument> mergeFailedDocuments(
      List<OcrJobFailureView> ocrJobFailures, List<OcrResultView> needsReuploadDocuments) {
    List<FailedDocument> merged = new ArrayList<>();
    if (ocrJobFailures != null) {
      merged.addAll(toFailedDocuments(ocrJobFailures));
    }
    if (needsReuploadDocuments != null) {
      needsReuploadDocuments.forEach(
          document -> merged.add(new FailedDocument(document.getAttachmentId(), null)));
    }
    return merged;
  }

  /** OCR 저널 행을 문서 단위 실패로 옮긴다. 사유는 행마다 ACL({@link OcrJobFailureView#reason()})로 번역한다. */
  private static List<FailedDocument> toFailedDocuments(List<OcrJobFailureView> ocrJobFailures) {
    return ocrJobFailures.stream()
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

  /** AI 차단 — 입력 가드레일 차단 또는 리포트 생성 LLM 거절({@code reports.status == BLOCKED}). */
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
   * 실패·재업로드 필요 문서 1건.
   *
   * @param attachmentId {@code report_attachments.id}. 저널·품질 판정 어느 쪽에도 없으면 null(§8 E9)
   * @param reason       문서별 실패 사유. {@code ai.ocr_job_failures} 기반(FAILED, 청구 fan-in
   *                     NEEDS_REUPLOAD)만 채워진다 — {@code ai.ocr_results} 기반(개별 문서 품질 게이트
   *                     NEEDS_REUPLOAD)은 다른 계약이라 대응하는 사유가 없어 null이다(§4-2, ACL 경계)
   */
  public record FailedDocument(@Nullable UUID attachmentId, @Nullable AnalysisFailureReason reason) {
  }
}
