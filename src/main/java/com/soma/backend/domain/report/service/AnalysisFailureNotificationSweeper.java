package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.event.AnalysisFailedEvent;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.PendingAnalysisFailureRow;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 분석(OCR·AI) 확정 실패 알림 스윕(design.md §7). AI 워커는 실패를 저널({@code ai.ocr_job_failures})에 남길 뿐
 * 이벤트를 쏘지 않으므로, 감지는 폴링일 수밖에 없다.
 *
 * <p><b>왜 조회 시점 주입이 아니라 스케줄러인가:</b> 무음 실패의 본질은 "사용자가 앱을 다시 열 이유가 없어서
 * 안 연다"는 것이다. 조회 API 호출 시점에 알림을 만들면 앱을 닫아둔 사용자에게는 아무 일도 일어나지 않는다.
 * 스윕은 FCM 푸시라는 유일한 실효 수단을 쓸 수 있고, 멱등 가드는 어느 쪽이든 똑같이 필요하다.
 *
 * <p><b>트랜잭션 경계:</b> 이 트랜잭션은 {@link Report} Aggregate 하나만 수정한다(통지 시각). 알림 행 생성
 * ({@code Notification} Aggregate)과 FCM 발송은 {@link AnalysisFailedEvent}를 통해 커밋 후(AFTER_COMMIT)로
 * 분리한다 — 기존 {@code ReviewProposalReceivedEvent} 경로와 동일한 구조다.
 *
 * <p><b>사이클 예외 격리:</b> 여기서 예외를 <b>삼키지</b> 않는다. 조회 실패(GRANT 미적용 등)를 트랜잭션 안에서
 * 삼키면 Hibernate가 세션을 rollback-only로 표시해 커밋 시점에 {@code UnexpectedRollbackException}이 다시
 * 터지기 때문이다. Spring의 고정 지연 스케줄러는 예외를 로깅하고 억제한 뒤 다음 주기를 그대로 잡으므로
 * ({@code TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER}), 일시 DB 오류로 데몬이 죽지 않는다. 다만 그 억제 때문에
 * 실패가 로그에만 남으므로, 세고 나서 그대로 다시 던지는 방식으로 카운터 하나만 얹었다(아래 관측성).
 *
 * <p><b>관측성(Micrometer):</b> 두 카운터를 {@code MeterRegistry}에 등록한다 — 이 프로젝트에서 애플리케이션
 * 정의 메트릭을 쓰는 첫 지점이라 기준이 되도록 명시적으로 사전 등록한다(태그 카디널리티는 enum 5값 + 고정 1값).
 *
 * <ul>
 *   <li>{@code analysis.failure.notified{reason}} → Prometheus {@code analysis_failure_notified_total} —
 *       사이클당 통지한 리포트 수를 대표 사유별로 센다. 사유별 합계가 곧 사이클 처리 건수다.</li>
 *   <li>{@code analysis.failure.sweep.errors} → {@code analysis_failure_sweep_errors_total} — 사이클이 예외로
 *       끝난 횟수. 스케줄러가 예외를 억제하는 탓에 이 값 말고는 실패를 지표로 볼 방법이 없다. 가장 흔한 원인은
 *       {@code GRANT SELECT ON ai.ocr_job_failures TO app_owner} 미적용이다(design.md §9-3).</li>
 * </ul>
 *
 * <p>메트릭은 트랜잭션 자원이 아니라 롤백돼도 되돌아가지 않는다 — 롤백된 사이클은 통지 카운터와 에러 카운터가
 * 함께 오르므로 대시보드에서 구분된다.
 */
@Slf4j
@Service
public class AnalysisFailureNotificationSweeper {

  /** 조회 창 — {@code last_failed_at}이 이 기간 안인 실패만 본다(무기한 스캔 방지, 부분 인덱스 범위 스캔). */
  private static final long LOOKBACK_DAYS = 7L;

  /**
   * 한 사이클 처리 상한(리포트 수). 알림 리스너가 {@code @Async}가 아니라 AFTER_COMMIT 스레드에서 FCM I/O를
   * 동기 수행하므로, 배치를 키우면 왕복이 직렬로 쌓인다. 늘려야 하면 비동기·아웃박스 전환이 선행돼야 한다.
   */
  private static final int BATCH_SIZE = 100;

  private final OcrJobFailureViewRepository ocrJobFailureViewRepository;
  private final ReportRepository reportRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final Map<AnalysisFailureReason, Counter> notifiedCounters = new EnumMap<>(AnalysisFailureReason.class);
  private final Counter sweepErrorCounter;

  /** 스윕 비활성화 스위치(테스트·ai 스키마 미가용 환경). 빈 조건부 대신 실행 시점 플래그로 no-op 처리한다. */
  @Value("${app.report.analysis-failure-sweep-enabled:true}")
  private boolean sweepEnabled;

  public AnalysisFailureNotificationSweeper(
      OcrJobFailureViewRepository ocrJobFailureViewRepository,
      ReportRepository reportRepository,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry) {
    this.ocrJobFailureViewRepository = ocrJobFailureViewRepository;
    this.reportRepository = reportRepository;
    this.eventPublisher = eventPublisher;
    for (AnalysisFailureReason reason : AnalysisFailureReason.values()) {
      notifiedCounters.put(reason, Counter.builder("analysis.failure.notified")
          .description("분석 확정 실패로 사용자에게 통지한 리포트 수(대표 사유별)")
          .tag("reason", reason.name())
          .register(meterRegistry));
    }
    this.sweepErrorCounter = Counter.builder("analysis.failure.sweep.errors")
        .description("분석 실패 알림 스윕 사이클이 예외로 끝난 횟수 — 가장 흔한 원인은 "
            + "GRANT SELECT ON ai.ocr_job_failures TO app_owner 미적용(design.md §9-3)")
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${app.report.analysis-failure-sweep-interval-ms:60000}")
  @Transactional
  public void sweep() {
    if (!sweepEnabled) {
      return;
    }
    try {
      sweepOnce();
    } catch (RuntimeException ex) {
      // 스케줄러(LOG_AND_SUPPRESS_ERROR_HANDLER)가 예외를 로그로만 남기고 삼키므로,
      // 지표로도 실패를 볼 수 있게 다시 던지기 전에 센다.
      sweepErrorCounter.increment();
      throw ex;
    }
  }

  private void sweepOnce() {
    LocalDateTime since = LocalDateTime.now().minusDays(LOOKBACK_DAYS);
    List<PendingAnalysisFailureRow> pending =
        ocrJobFailureViewRepository.findPendingNotification(since, BATCH_SIZE);
    if (pending.isEmpty()) {
      return;
    }

    List<UUID> reportIds = pending.stream().map(PendingAnalysisFailureRow::reportId).toList();
    // 대표 사유는 해당 리포트의 실패 행 '전부'로 계산한다 — 배치 경계에서 사유가 잘리면
    // masking_residual이 빠진 채 unreadable_file이 대표가 되어 잘못된 재업로드 안내가 나갈 수 있다(§8 E5).
    Map<UUID, List<OcrJobFailureView>> failuresByReport =
        ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(reportIds).stream()
            .filter(failure -> failure.getReportId() != null)
            .collect(Collectors.groupingBy(OcrJobFailureView::getReportId));
    Map<UUID, Report> reports = reportRepository.findAllByIdIn(reportIds).stream()
        .collect(Collectors.toMap(Report::getId, Function.identity()));

    int notified = 0;
    for (PendingAnalysisFailureRow row : pending) {
      if (notifyIfFailed(row, reports.get(row.reportId()), failuresByReport)) {
        notified++;
      }
    }
    if (notified > 0) {
      log.info("분석 실패 알림 스윕: {}건 통지(조회 대상 {}건, lookback {}일)", notified, pending.size(), LOOKBACK_DAYS);
    }
  }

  /**
   * 리포트 1건을 판정해 통지 대상이면 통지 시각을 기록하고 이벤트를 발행한다.
   *
   * <p>조회와 통지 사이에 AI 초안이 생기거나 실패 행이 지워졌을 수 있으므로(정상 회복 — §8 E2·E3) 여기서
   * 다시 판정한다. 통지 시각 기록({@link Report#markAnalysisFailureNotified()})이 단방향 멱등 가드라
   * 스윕이 겹쳐 돌아도 알림은 리포트당 1건이다.
   */
  private boolean notifyIfFailed(
      PendingAnalysisFailureRow row, Report report, Map<UUID, List<OcrJobFailureView>> failuresByReport) {
    if (report == null) {
      return false;
    }
    ReportAnalysis analysis =
        ReportAnalysis.of(report, failuresByReport.getOrDefault(row.reportId(), List.of()));
    if (!analysis.isFailed() || !report.markAnalysisFailureNotified()) {
      return false;
    }
    eventPublisher.publishEvent(new AnalysisFailedEvent(row.userId(), row.reportId(), analysis.reason()));
    notifiedCounters.get(analysis.reason()).increment();
    return true;
  }
}
