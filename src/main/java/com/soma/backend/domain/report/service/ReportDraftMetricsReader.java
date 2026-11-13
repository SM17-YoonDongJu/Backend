package com.soma.backend.domain.report.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.ReportDraftMetricsView;
import com.soma.backend.domain.report.repository.ReportDraftMetricsViewRepository;

/**
 * {@code ai.report_drafts}의 metrics 조회를 별도 트랜잭션(REQUIRES_NEW, readOnly)으로 격리한다.
 *
 * <p>{@code ai} 스키마는 다른 팀 소유라 GRANT 회수·테이블 부재로 조회가 실패할 수 있다. REQUIRES_NEW는 어떤 호출
 * 위치에서든 그 실패가 호출자 트랜잭션을 rollback-only로 오염시키지 않게 하려는 것이다(별도 Bean이라 자가 호출
 * 문제도 없다). 단 커넥션 비용은 호출자가 진다 — 바깥 트랜잭션이 있는 곳에서 부르면 그 커넥션을 쥔 채 커넥션을
 * 하나 더 요구한다. 그래서 이 리더를 부르는 경로({@link ReportAnalysisMetricsQueryService} →
 * {@link ReportAnalysisMetricsResolver})에는 바깥 트랜잭션을 두지 않는다.
 *
 * <p><b>예외를 여기서 삼키지 않는다</b>({@link TerminalFailureJournalReader}와 같은 근거). 삼키면 Spring이 이미
 * aborted 상태인 Postgres 세션을 커밋하려다 다시 실패한다. 예외를 그대로 던져 Spring이 이 트랜잭션을 롤백하게 하고,
 * 호출자({@link ReportAnalysisMetricsResolver})가 이 호출이 끝난 뒤에 잡는다.
 */
@Component
@RequiredArgsConstructor
public class ReportDraftMetricsReader {

  private final ReportDraftMetricsViewRepository reportDraftMetricsViewRepository;

  /** 리포트의 초안 수치 행. 초안이 없으면 빈 Optional이다. 조회 실패 시 예외를 그대로 던진다(클래스 javadoc). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<ReportDraftMetricsView> findByReportId(UUID reportId) {
    return reportDraftMetricsViewRepository.findByReportId(reportId);
  }
}
