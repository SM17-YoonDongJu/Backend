package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 미채택(NOT_SELECTED) 자동 전이 스윕. 리포트 접수({@code created_at}) 후 {@link #NOT_SELECTED_AFTER_DAYS}일이
 * 지나도록 상담 완료(CLOSED)되지 못했거나(케이스1) 검수를 하나도 받지 못한(케이스2) 리포트를 NOT_SELECTED로
 * 전이한다. 대상은 검수 대기(AWAITING_INSPECTION)·채택 대기(AWAITING_ADOPTION)뿐이며, 이미 CLOSED·COUNSELING·
 * NOT_SELECTED인 리포트는 제외한다(전이 대상 상태가 아니므로 쿼리에서 자연히 빠진다). NOT_SELECTED가 되면
 * 신규 검수는 차단되지만({@link Report#applyReviewStart()}가 예외) 진행 중인 상담/채팅은 닫지 않는다.
 *
 * <p>전이 로직은 엔티티({@link Report#markNotSelected()})가 소유하고, 스윕 트랜잭션 커밋 시 dirty checking으로
 * 반영된다. 주기는 {@code app.report.not-selected-sweep-interval-ms}로 조정한다(기본 1시간).
 */
@Service
@RequiredArgsConstructor
public class ReportNotSelectionSweeper {

  private static final Logger log = LoggerFactory.getLogger(ReportNotSelectionSweeper.class);

  /** 접수 후 이 기간(일)이 지나도록 미채택이면 NOT_SELECTED로 전이한다. */
  private static final long NOT_SELECTED_AFTER_DAYS = 7L;

  /** 미채택 전이 대상 상태 — 검수 대기·채택 대기(제안이 아직 채택/종결되지 않은 단계). */
  private static final Set<ReportStatus> SWEEP_SOURCES =
      Set.of(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION);

  private final ReportRepository reportRepository;

  @Scheduled(fixedDelayString = "${app.report.not-selected-sweep-interval-ms:3600000}")
  @Transactional
  public void sweep() {
    LocalDateTime threshold = LocalDateTime.now().minusDays(NOT_SELECTED_AFTER_DAYS);
    List<Report> expired = reportRepository.findExpiredForNotSelection(SWEEP_SOURCES, threshold);
    for (Report report : expired) {
      report.markNotSelected();
    }
    if (!expired.isEmpty()) {
      log.info("미채택 자동 전이: {}건을 NOT_SELECTED로 변경 (기준 {}일, threshold={})",
          expired.size(), NOT_SELECTED_AFTER_DAYS, threshold);
    }
  }
}
