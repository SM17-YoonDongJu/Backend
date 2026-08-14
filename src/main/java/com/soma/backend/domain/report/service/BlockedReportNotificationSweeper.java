package com.soma.backend.domain.report.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.event.ReportBlockedEvent;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * BLOCKED(AI 입력 가드레일 차단) 알림 스윕(design.md F1 후속). {@link AnalysisFailureNotificationSweeper}와
 * 같은 "무음 실패는 폴링 스윕으로 잡는다" 원칙을 따르되, 대상 저널이 다르다 — BLOCKED는 가드레일이 OCR·초안
 * 생성 <b>이전</b>에 파이프라인을 끊어버려 {@code ai.ocr_job_failures}에 흔적이 안 남는다. 그래서 이 스윕은
 * 저널이 아니라 {@code reports.status}를 직접 스캔한다.
 *
 * <p><b>트랜잭션 경계:</b> 이 트랜잭션은 {@link Report} Aggregate 하나만 수정한다(통지 시각). 알림 행 생성
 * ({@code Notification} Aggregate)과 FCM 발송은 {@link ReportBlockedEvent}를 통해 커밋 후(AFTER_COMMIT)로
 * 분리한다 — {@code AnalysisFailureNotificationSweeper}·{@code ReviewProposalReceivedEvent}와 동일한 구조다.
 *
 * <p><b>관측성(Micrometer):</b> {@code report.blocked.notified} → Prometheus
 * {@code report_blocked_notified_total}(사이클당 통지 건수), {@code report.blocked.sweep.errors} →
 * {@code report_blocked_sweep_errors_total}(사이클이 예외로 끝난 횟수).
 */
@Slf4j
@Service
public class BlockedReportNotificationSweeper {

  /** 한 사이클 처리 상한. AFTER_COMMIT 리스너가 FCM I/O를 동기 수행하므로 무제한으로 늘리지 않는다. */
  private static final int BATCH_SIZE = 100;

  private final ReportRepository reportRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final Counter notifiedCounter;
  private final Counter sweepErrorCounter;

  /** 스윕 비활성화 스위치(테스트 환경). */
  @Value("${app.report.blocked-sweep-enabled:true}")
  private boolean sweepEnabled;

  public BlockedReportNotificationSweeper(
      ReportRepository reportRepository, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
    this.reportRepository = reportRepository;
    this.eventPublisher = eventPublisher;
    this.notifiedCounter = Counter.builder("report.blocked.notified")
        .description("AI 입력 가드레일 차단으로 사용자에게 통지한 리포트 수")
        .register(meterRegistry);
    this.sweepErrorCounter = Counter.builder("report.blocked.sweep.errors")
        .description("BLOCKED 알림 스윕 사이클이 예외로 끝난 횟수")
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${app.report.blocked-sweep-interval-ms:60000}")
  @Transactional
  public void sweep() {
    if (!sweepEnabled) {
      return;
    }
    try {
      sweepOnce();
    } catch (RuntimeException ex) {
      sweepErrorCounter.increment();
      throw ex;
    }
  }

  private void sweepOnce() {
    List<Report> pending = reportRepository.findAllByStatusAndBlockedNotifiedAtIsNull(
        ReportStatus.BLOCKED, PageRequest.of(0, BATCH_SIZE));
    if (pending.isEmpty()) {
      return;
    }

    int notified = 0;
    for (Report report : pending) {
      if (report.markBlockedNotified()) {
        eventPublisher.publishEvent(new ReportBlockedEvent(report.getUserId(), report.getId()));
        notifiedCounter.increment();
        notified++;
      }
    }
    if (notified > 0) {
      log.info("BLOCKED 알림 스윕: {}건 통지(조회 대상 {}건)", notified, pending.size());
    }
  }
}
