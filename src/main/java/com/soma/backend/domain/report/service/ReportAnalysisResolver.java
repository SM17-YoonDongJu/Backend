package com.soma.backend.domain.report.service;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.entity.ClaimReadinessView;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.OcrResultView;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportJobFailureView;
import com.soma.backend.domain.report.entity.ReuploadCause;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 리포트 분석 처리 상태를 리포트 묶음 단위로 판정하는 조회 협력 객체. 목록·상세({@code ReportQueryService})와
 * 폴링 단건 조회({@code ReportAnalysisStatusQueryService})가 함께 쓰고, 재업로드 푸시({@code ReportNotificationListener})도
 * {@link #reuploadCausesOrEmpty}로 같은 재업로드 원인을 읽는다 — 판정 규칙 자체는 {@link ReportAnalysis}가 소유하고,
 * 여기는 판정에 필요한 저널·문서·재업로드 원인 조회와 그 실패 흡수(degrade)만 맡는다.
 *
 * <p>유스케이스 서비스끼리 서로 부르지 않도록 {@code ReportAnalysisStatusQueryService}에서 떼어냈다. 별도 Bean이라
 * {@link #resolveAll}의 {@code REQUIRES_NEW}가 호출 위치와 무관하게 항상 적용된다.
 *
 * <p><b>관측성:</b> 실패 저널을 못 읽어 판정을 낮출 때마다 {@code analysis.journal.degraded{journal}}
 * (Prometheus {@code analysis_journal_degraded_total})을 올린다. degrade는 응답을 200으로 지키는 대신 FAILED를
 * PROCESSING으로 가리므로, 이 값 말고는 "실패가 안 보이고 있다"는 사실을 지표로 볼 방법이 없다. 재업로드 사유
 * ({@code ai.claim_readiness})를 못 읽어 NEEDS_REUPLOAD 안내를 일반 문구로 낮출 때는 별도 카운터
 * {@code analysis.reupload_reason.degraded{cause}}(Prometheus {@code analysis_reupload_reason_degraded_total})를
 * 올린다 — 상태는 맞고 문구만 낮아지는 다른 등급이라 저널 카운터와 섞지 않는다.
 */
@Slf4j
@Component
public class ReportAnalysisResolver {

  /** 저널 degrade 카운터 이름. 분석 실패 알림 스윕도 같은 이름·태그로 센다. */
  static final String JOURNAL_DEGRADED_METRIC = "analysis.journal.degraded";

  /** 재업로드 사유 degrade 카운터 이름(태그 {@code cause}). 테스트가 같은 이름으로 읽는다. */
  static final String REUPLOAD_REASON_DEGRADED_METRIC = "analysis.reupload_reason.degraded";

  private final ReportRepository reportRepository;
  private final TerminalFailureJournalReader terminalFailureJournalReader;
  private final NeedsReuploadDocumentReader needsReuploadDocumentReader;
  private final ClaimReadinessReader claimReadinessReader;
  private final Counter ocrJobJournalDegraded;
  private final Counter reportJobJournalDegraded;
  private final Counter reuploadReasonSchemaMissing;
  private final Counter reuploadReasonQueryFailed;
  private final Counter reuploadReasonAmbiguousRows;

  /**
   * 저널별·재업로드 사유 원인별 degrade 카운터를 미리 등록해, 한 번도 degrade하지 않은 것도 대시보드에 0으로 보이게
   * 한다.
   */
  public ReportAnalysisResolver(
      ReportRepository reportRepository,
      TerminalFailureJournalReader terminalFailureJournalReader,
      NeedsReuploadDocumentReader needsReuploadDocumentReader,
      ClaimReadinessReader claimReadinessReader,
      MeterRegistry meterRegistry) {
    this.reportRepository = reportRepository;
    this.terminalFailureJournalReader = terminalFailureJournalReader;
    this.needsReuploadDocumentReader = needsReuploadDocumentReader;
    this.claimReadinessReader = claimReadinessReader;
    this.ocrJobJournalDegraded = journalDegradedCounter(meterRegistry, "ocr_job");
    this.reportJobJournalDegraded = journalDegradedCounter(meterRegistry, "report_job");
    this.reuploadReasonSchemaMissing = reuploadReasonDegradedCounter(meterRegistry, "schema_missing");
    this.reuploadReasonQueryFailed = reuploadReasonDegradedCounter(meterRegistry, "query_failed");
    this.reuploadReasonAmbiguousRows = reuploadReasonDegradedCounter(meterRegistry, "ambiguous_rows");
  }

  /** 저널별 degrade 카운터. 같은 이름·태그면 레지스트리가 같은 인스턴스를 돌려주므로 스윕과 값을 공유한다. */
  static Counter journalDegradedCounter(MeterRegistry meterRegistry, String journal) {
    return Counter.builder(JOURNAL_DEGRADED_METRIC)
        .description("실패 저널을 읽지 못해 분석 판정·통지를 낮춘 횟수(저널별) — report_job이 계속 오르면 "
            + "AI 마이그레이션 012 적용과 app_owner 권한을 확인한다")
        .tag("journal", journal)
        .register(meterRegistry);
  }

  /** 재업로드 사유 degrade 카운터(원인별). */
  private static Counter reuploadReasonDegradedCounter(MeterRegistry meterRegistry, String cause) {
    return Counter.builder(REUPLOAD_REASON_DEGRADED_METRIC)
        .description("재업로드 사유(ai.claim_readiness)를 쓰지 못해 NEEDS_REUPLOAD 안내를 일반 문구로 낮춘 횟수(원인별) — "
            + "schema_missing이 계속 오르면 AI 마이그레이션 013 적용을, query_failed면 backend 롤"
            + "(app_owner·app_owner_prod)의 컬럼 GRANT를, ambiguous_rows면 AI의 리포트당 1행 계약을 확인한다")
        .tag("cause", cause)
        .register(meterRegistry);
  }

  /**
   * 목록·상세 응답에 붙일 분석 상태를 리포트 묶음 단위로 한 번에 판정한다(리포트·실패 저널 2종을 각각 배치 1회씩, 품질
   * 미달 문서·재업로드 원인은 NEEDS_REUPLOAD 리포트가 있을 때만 배치 1회씩). 카드 쿼리에 실패 뷰를 조인하지 않는
   * 이유는 실패 문서 N건당 카드 행이 복제되기 때문이다.
   *
   * <p><b>리포트를 먼저 읽는다.</b> 품질 미달 문서({@code ai.ocr_results})와 재업로드 원인({@code ai.claim_readiness})은
   * NEEDS_REUPLOAD 판정에서만 쓰이므로({@link ReportAnalysis#requiresReuploadInputs}) 그 리포트 id만 조회한다 — 다른
   * 리포트만 있는 페이지는 두 조회와 그 커넥션 획득을 아예 하지 않는다. 리포트 조회는 이 메서드의 트랜잭션 커넥션으로
   * 하므로 순서를 바꿔도 동시 점유 커넥션은 늘지 않는다.
   *
   * <p><b>{@code REQUIRES_NEW}인 이유(중요):</b> {@code ai} 스키마는 다른 팀 소유라 GRANT 미적용·테이블 부재로
   * 조회가 실패할 수 있는데, 그 실패를 호출자의 트랜잭션 안에서 잡으면 Hibernate가 JDBC 예외 시 세션을
   * rollback-only로 표시해 커밋 시점에 {@code UnexpectedRollbackException}이 터진다(= degrade가 무의미해지고
   * 목록/상세가 통째로 500). 별도 트랜잭션으로 떼어내 실패 반경을 이 조회로 가둔다.
   *
   * <p><b>저널 조회 실패는 리포트 단위 판정까지 버리지 않는다.</b> {@link TerminalFailureJournalReader}가
   * 별도 Bean·별도 REQUIRES_NEW로 저널만 조회한다. 그 조회가 실패하면(GRANT 미적용 등) 예외가 이 메서드
   * 호출 지점까지 올라오지만, 실패한 건 이미 롤백까지 끝난 별개의 트랜잭션이라 <b>여기서 잡아도 이 메서드
   * 자신의 트랜잭션은 오염되지 않는다</b>(자가 호출이 아니라 별도 Bean 경유라 가능 — 같은 트랜잭션 안에서
   * 예외만 삼키면 Postgres가 이후 문장을 전부 "aborted transaction"으로 막는다). 예외를 잡으면 빈 실패
   * 목록으로 계속 진행한다 — {@code BLOCKED}·{@code NEEDS_REUPLOAD}는 {@code reports.status}만으로
   * 판정되고 저널이 전혀 필요 없으므로 {@link ReportAnalysis#of}가 그대로 두 상태를 살려낸다. 저널에 의존하는
   * {@code FAILED} 판정만 실질적으로 {@code PROCESSING}으로 낮아진다 — 저널 없이는 확정 실패 여부를 알 수
   * 없기 때문이다. 두 저널은 따로 조회·흡수하므로 한쪽(예: 아직 012가 없는 리포트 생성 저널)이 실패해도 다른
   * 쪽의 FAILED는 그대로 살아남는다. 호출자(예: {@code ReportQueryService.analysesOrDegrade})의 빈 맵 폴백은
   * 이 메서드 자체가 실패하는(예: 리포트 배치 조회 실패) 더 드문 경우를 위한 마지막 방어선으로만 남는다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, ReportAnalysis> resolveAll(Collection<UUID> reportIds) {
    if (reportIds == null || reportIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> targets = reportIds.stream().filter(Objects::nonNull).distinct().toList();
    if (targets.isEmpty()) {
      return Map.of();
    }
    List<Report> reports = reportRepository.findAllByIdIn(targets);
    List<UUID> needsReuploadIds = reports.stream()
        .filter(ReportAnalysis::requiresReuploadInputs)
        .map(Report::getId)
        .toList();
    // 두 저널은 상태 자체를 정하는 입력이라 요청한 리포트 전부로 조회한다(FAILED·NEEDS_REUPLOAD 문서 공용).
    Map<UUID, List<OcrJobFailureView>> ocrFailuresByReport = ocrJobFailuresOrEmpty(targets);
    Map<UUID, List<ReportJobFailureView>> reportJobFailuresByReport = reportJobFailuresOrEmpty(targets);
    Map<UUID, List<OcrResultView>> needsReuploadDocumentsByReport = needsReuploadDocumentsOrEmpty(needsReuploadIds);
    Map<UUID, ReuploadCause> reuploadCauses = reuploadCausesOrEmpty(needsReuploadIds);

    Map<UUID, ReportAnalysis> analyses = new HashMap<>();
    for (Report report : reports) {
      UUID reportId = report.getId();
      analyses.put(reportId, ReportAnalysis.of(report,
          ocrFailuresByReport.getOrDefault(reportId, List.of()),
          reportJobFailuresByReport.getOrDefault(reportId, List.of()),
          needsReuploadDocumentsByReport.getOrDefault(reportId, List.of()),
          reuploadCauses.get(reportId)));
    }
    return analyses;
  }

  /**
   * 목록·상세용 — OCR 확정 실패 저널 조회가 실패하면 빈 맵으로 degrade한다. 리더의 REQUIRES_NEW 시작(커넥션
   * 획득)이 실패하는 {@link TransactionException}도 흡수한다 — 시작 실패는 이 메서드의 트랜잭션을 되살린 뒤
   * 던져지므로 이어지는 조회에 영향이 없다.
   */
  private Map<UUID, List<OcrJobFailureView>> ocrJobFailuresOrEmpty(List<UUID> targets) {
    try {
      return terminalFailureJournalReader.findTerminalOcrJobFailures(targets);
    } catch (DataAccessException | TransactionException ex) {
      ocrJobJournalDegraded.increment();
      log.warn("OCR 확정 실패 저널 조회 실패 — OCR 단계 FAILED 판정만 PROCESSING으로 degrade한다(대상 {}건). "
          + "BLOCKED·NEEDS_REUPLOAD는 reports.status만으로 판정되므로 영향 없다. cause={}",
          targets.size(), ex.getMostSpecificCause().getMessage());
      return Map.of();
    }
  }

  /**
   * 목록·상세용 — 리포트 생성 확정 실패 저널 조회가 어떤 이유로 실패하든(REQUIRES_NEW 시작 실패 포함) 빈 맵으로
   * degrade한다(목록·상세는 가용성이 우선이라 OCR 저널과 같은 원칙, 알림 스윕과도 같은 기준). 리포트 생성 단계
   * FAILED만 PROCESSING으로 보이고 나머지 판정은 그대로다.
   */
  private Map<UUID, List<ReportJobFailureView>> reportJobFailuresOrEmpty(List<UUID> targets) {
    try {
      return terminalFailureJournalReader.findTerminalReportJobFailures(targets);
    } catch (DataAccessException | TransactionException ex) {
      onReportJobJournalUnavailable(ex, targets.size());
      return Map.of();
    }
  }

  /**
   * 단건 폴링용 — 012가 아직 적용되지 않아 테이블이 없을 때(SQLState 42P01)만 빈 맵으로 낮추고, 그 밖의 조회
   * 실패는 그대로 던진다. 012는 AI report 워커가 기동할 때 환경별로 적용하므로 Backend가 먼저 나간 환경에서
   * 폴링 전체가 500이 되면 안 되지만, 테이블이 있는데도 못 읽는 상황(권한 회수·컬럼 변경 등)에 PROCESSING을
   * 내리면 OCR 저널과 같은 "영원히 처리 중" 거짓이 된다(design.md §8 E14) — 그건 OCR 저널처럼 500으로 드러낸다.
   * 커넥션을 못 얻는 {@link TransactionException}도 같은 이유로 잡지 않는다.
   */
  public Map<UUID, List<ReportJobFailureView>> reportJobFailuresOrEmptyIfJournalMissing(List<UUID> targets) {
    try {
      return terminalFailureJournalReader.findTerminalReportJobFailures(targets);
    } catch (DataAccessException ex) {
      if (!TerminalFailureJournalReader.isJournalMissing(ex)) {
        throw ex;
      }
      onReportJobJournalUnavailable(ex, targets.size());
      return Map.of();
    }
  }

  /**
   * degrade를 센다. 테이블이 아직 없는 과도기는 예상된 상태라 요청마다 경고를 쌓지 않고 DEBUG로만 남긴다 —
   * 카운터가 그 상태를 대신 드러낸다. 그 밖의 원인은 진짜 장애라 WARN이다.
   */
  private void onReportJobJournalUnavailable(NestedRuntimeException ex, int targetCount) {
    reportJobJournalDegraded.increment();
    if (TerminalFailureJournalReader.isJournalMissing(ex)) {
      log.debug("리포트 생성 실패 저널(ai.report_job_failures)이 아직 없다(AI 마이그레이션 012 미적용) — "
          + "리포트 생성 단계 FAILED 없이 판정한다(대상 {}건)", targetCount);
      return;
    }
    log.warn("리포트 생성 실패 저널(ai.report_job_failures) 조회 실패 — 리포트 생성 단계 FAILED만 "
        + "PROCESSING으로 degrade한다(대상 {}건). cause={}", targetCount, ex.getMostSpecificCause().getMessage());
  }

  /**
   * 품질 미달 문서 조회 실패는 항상 빈 맵으로 degrade한다(GRANT 미배포면 매번 실패). NEEDS_REUPLOAD 상태
   * 판정 자체는 {@code reports.status}만으로 이미 정확하므로, 이 조회는 문서 특정만 못 할 뿐이다. 리더의
   * REQUIRES_NEW 시작(커넥션 획득)이 실패하는 {@link TransactionException}도 같은 이유로 흡수한다 — 시작 실패는 호출자의
   * 트랜잭션을 되살린 뒤 던져지므로 흡수해도 안전하다(형제 조회 {@link #reuploadCausesOrEmpty}와 같은 기준).
   *
   * @param needsReuploadReportIds NEEDS_REUPLOAD 리포트 id만 넘긴다({@link ReportAnalysis#requiresReuploadInputs}).
   *                               비어 있으면 조회하지 않는다
   */
  public Map<UUID, List<OcrResultView>> needsReuploadDocumentsOrEmpty(List<UUID> needsReuploadReportIds) {
    if (needsReuploadReportIds == null || needsReuploadReportIds.isEmpty()) {
      return Map.of();
    }
    try {
      return needsReuploadDocumentReader.findNeedsReuploadDocuments(needsReuploadReportIds);
    } catch (DataAccessException | TransactionException ex) {
      log.debug("품질 미달 문서 조회 실패(대상 {}건, GRANT 미배포 시 정상) — 문서 특정 없이 진행한다. exception={}, "
          + "cause={}, sqlState={}", needsReuploadReportIds.size(), ex.getClass().getSimpleName(),
          ex.getMostSpecificCause().getClass().getSimpleName(), sqlStateOf(ex));
      return Map.of();
    }
  }

  /**
   * NEEDS_REUPLOAD 리포트의 재업로드 원인({@code ai.claim_readiness})을 읽는다. 목록·상세·폴링과 재업로드 푸시가 같은
   * 메서드를 써서 조회 응답과 푸시 문구가 어긋나지 않는다.
   *
   * <p><b>사유가 없으면 맵에서 빠진다</b> — 행 없음·{@code reupload_reason} NULL·{@code unreadable}·모르는 값이면 그
   * 리포트는 기존 일반 안내다. 계약상 리포트당 최대 1행이라 <b>행이 2개 이상이면</b>(계약 위반) 어느 사유를 믿을지 근거가
   * 없어 그 리포트만 일반 안내로 낮추고 {@code ambiguous_rows}를 센다.
   *
   * <p><b>조회 실패는 어떤 원인이든 빈 맵으로 낮춘다</b>(폴링 포함). 상태(NEEDS_REUPLOAD)는 {@code reports.status}로 이미
   * 정확하고 문구만 세분하는 조회라, 실패를 드러내려고 500을 낼 이유가 없다. 테이블·컬럼이 아직 없는 과도기(42P01·42703,
   * AI 010·013 미적용)는 예상된 상태라 DEBUG로만 남기고 {@code schema_missing}을, 그 밖(권한 없음 42501·커넥션 획득
   * 실패 등)은 WARN과 {@code query_failed}를 센다. 리더의 REQUIRES_NEW는 이 메서드로 돌아오기 전에 롤백까지 끝나므로
   * 여기서 잡아도 호출자의 트랜잭션은 오염되지 않는다.
   *
   * <p>로그에는 대상 건수·reportId·예외 클래스 이름·SQLState만 남긴다 — DB 예외 메시지(쿼리 조각)와 {@code claim_id}는
   * 남기지 않는다.
   *
   * @param needsReuploadReportIds NEEDS_REUPLOAD 리포트 id만 넘긴다({@link ReportAnalysis#requiresReuploadInputs}).
   *                               비어 있으면 조회하지 않는다
   * @return 리포트 id → 재업로드 원인. 사유별 안내가 확인된 리포트만 담긴다
   */
  public Map<UUID, ReuploadCause> reuploadCausesOrEmpty(List<UUID> needsReuploadReportIds) {
    if (needsReuploadReportIds == null || needsReuploadReportIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<ClaimReadinessView>> rowsByReport;
    try {
      rowsByReport = claimReadinessReader.findByReportIds(needsReuploadReportIds);
    } catch (DataAccessException | TransactionException ex) {
      onReuploadReasonUnavailable(ex, needsReuploadReportIds.size());
      return Map.of();
    }
    Map<UUID, ReuploadCause> causes = new HashMap<>();
    for (Map.Entry<UUID, List<ClaimReadinessView>> entry : rowsByReport.entrySet()) {
      List<ClaimReadinessView> rows = entry.getValue();
      if (rows.size() > 1) {
        reuploadReasonAmbiguousRows.increment();
        log.warn("재업로드 사유 행이 리포트당 1개를 넘는다(AI 계약 위반) — 이 리포트는 일반 재업로드 안내로 낸다. "
            + "reportId={}, rows={}", entry.getKey(), rows.size());
        continue;
      }
      ReuploadCause cause = rows.isEmpty() ? null : rows.getFirst().reuploadCause();
      if (cause != null) {
        causes.put(entry.getKey(), cause);
      }
    }
    return causes;
  }

  /** 재업로드 사유 조회 실패를 원인별로 세고 남긴다(테이블·컬럼 미적용 과도기는 DEBUG, 그 밖은 WARN). */
  private void onReuploadReasonUnavailable(NestedRuntimeException ex, int targetCount) {
    String sqlState = sqlStateOf(ex);
    if (ClaimReadinessReader.isSchemaMissing(ex)) {
      reuploadReasonSchemaMissing.increment();
      log.debug("재업로드 사유(ai.claim_readiness)를 아직 읽을 수 없다(AI 마이그레이션 010·013 미적용) — 일반 재업로드 "
          + "안내로 낸다(대상 {}건, sqlState={})", targetCount, sqlState);
      return;
    }
    reuploadReasonQueryFailed.increment();
    log.warn("재업로드 사유(ai.claim_readiness) 조회 실패 — 일반 재업로드 안내로 낸다(대상 {}건). exception={}, "
        + "cause={}, sqlState={}", targetCount, ex.getClass().getSimpleName(),
        ex.getMostSpecificCause().getClass().getSimpleName(), sqlState);
  }

  /** DB 오류의 SQLState. 원인 사슬 끝이 {@link SQLException}이 아니면(커넥션 획득 실패 일부 등) null이다. */
  private static @Nullable String sqlStateOf(NestedRuntimeException ex) {
    return ex.getMostSpecificCause() instanceof SQLException sqlException ? sqlException.getSQLState() : null;
  }
}
