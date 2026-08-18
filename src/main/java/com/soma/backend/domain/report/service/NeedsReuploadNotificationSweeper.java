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
import com.soma.backend.domain.report.entity.event.ReportNeedsReuploadEvent;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * NEEDS_REUPLOAD(OCR 품질 미달 — 재업로드 필요) 알림 스윕. {@link BlockedReportNotificationSweeper}와 같은
 * "무음 정지는 폴링 스윕으로 잡는다" 원칙을 따르되 대상 신호가 다르다 — 이 판정은 OCR '실패'가 아니라
 * '품질 판정'이라 {@code ai.ocr_job_failures}에 행이 남지 않으므로 저널 기반
 * {@link AnalysisFailureNotificationSweeper}가 절대 잡지 못한다. 그래서 저널이 아니라
 * {@code reports.status}를 직접 스캔한다.
 *
 * <p><b>기존 스윕에 통합하지 않은 이유:</b> 멱등 가드 컬럼이 다르다. 현재 조회는 Spring Data 파생 쿼리라
 * 컬럼명이 메서드 이름에 박혀 있어 {@code (status, 가드 컬럼)} 파라미터화가 불가능하고, 가드 컬럼을 공유하면
 * {@code blocked_notified_at}이 NEEDS_REUPLOAD 리포트에 채워지는 오독과 상태별 통지 지표 소실을 낳는다.
 *
 * <p><b>트랜잭션 경계:</b> 이 트랜잭션은 {@link Report} Aggregate 하나만 수정한다(통지 시각). 알림 행 생성
 * ({@code Notification} Aggregate)과 FCM 발송은 {@link ReportNeedsReuploadEvent}를 통해 커밋 후(AFTER_COMMIT)로
 * 분리한다 — 알림 발송이 실패해도 이미 커밋된 통지 시각에는 영향이 없다(best-effort, at-most-once).
 *
 * <p><b>관측성(Micrometer):</b> {@code report.needs_reupload.notified} → Prometheus
 * {@code report_needs_reupload_notified_total}(사이클당 통지 건수), {@code report.needs_reupload.sweep.errors}
 * → {@code report_needs_reupload_sweep_errors_total}(사이클이 예외로 끝난 횟수).
 */
@Slf4j
@Service
public class NeedsReuploadNotificationSweeper {

  /** 한 사이클 처리 상한. AFTER_COMMIT 리스너가 FCM I/O를 동기 수행하므로 무제한으로 늘리지 않는다. */
  private static final int BATCH_SIZE = 100;

  private final ReportRepository reportRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final Counter notifiedCounter;
  private final Counter sweepErrorCounter;

  /** 스윕 비활성화 스위치(테스트 환경). */
  @Value("${app.report.needs-reupload-sweep-enabled:true}")
  private boolean sweepEnabled;

  public NeedsReuploadNotificationSweeper(
      ReportRepository reportRepository, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
    this.reportRepository = reportRepository;
    this.eventPublisher = eventPublisher;
    this.notifiedCounter = Counter.builder("report.needs_reupload.notified")
        .description("OCR 품질 미달(재업로드 필요)로 사용자에게 통지한 리포트 수")
        .register(meterRegistry);
    this.sweepErrorCounter = Counter.builder("report.needs_reupload.sweep.errors")
        .description("NEEDS_REUPLOAD 알림 스윕 사이클이 예외로 끝난 횟수")
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${app.report.needs-reupload-sweep-interval-ms:60000}")
  @Transactional
  public void sweep() {
    if (!sweepEnabled) {
      return;
    }
    try {
      sweepOnce();
    } catch (RuntimeException ex) {
      // 삼키지 않는다 — 예외를 잡아도 Hibernate 세션이 이미 rollback-only라 커밋 시점에
      // UnexpectedRollbackException으로 다시 터진다. 지표만 남기고 그대로 던진다.
      sweepErrorCounter.increment();
      throw ex;
    }
  }

  private void sweepOnce() {
    List<Report> pending = reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(
        ReportStatus.NEEDS_REUPLOAD, PageRequest.of(0, BATCH_SIZE));
    if (pending.isEmpty()) {
      return;
    }

    int notified = 0;
    for (Report report : pending) {
      if (report.markNeedsReuploadNotified()) {
        eventPublisher.publishEvent(new ReportNeedsReuploadEvent(report.getUserId(), report.getId()));
        notifiedCounter.increment();
        notified++;
      }
    }
    if (notified > 0) {
      log.info("NEEDS_REUPLOAD 알림 스윕: {}건 통지(조회 대상 {}건)", notified, pending.size());
    }
  }
}
