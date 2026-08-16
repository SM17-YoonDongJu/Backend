package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;

/**
 * {@code ai.ocr_job_failures} 확정 실패 배치 조회를 별도 트랜잭션(REQUIRES_NEW)으로 격리한다.
 *
 * <p>PostgreSQL은 트랜잭션 안에서 문장 하나가 실패하면 커밋·롤백 전까지 같은 트랜잭션의 이후 모든 문장이
 * "current transaction is aborted"로 연쇄 실패한다. {@link ReportAnalysisStatusQueryService#resolveAll}이
 * 같은 트랜잭션 안에서 이 조회를 직접 하고 예외를 잡기만 하면, 저널 조회는 실패해도 그 뒤에 이어지는
 * {@code reportRepository.findAllByIdIn} 호출까지 같은 트랜잭션이라 함께 실패해 버린다(자가 호출이라
 * {@code @Transactional} 재적용도 안 된다 — 별도 Bean으로 빼야 실제로 새 트랜잭션·새 커넥션이 열린다).
 *
 * <p><b>예외를 여기서 삼키지 않는다.</b> 삼기면 Spring이 "메서드가 정상 종료했다"고 보고 이 REQUIRES_NEW
 * 트랜잭션을 커밋하려 드는데, 이미 Postgres 세션이 aborted 상태라 커밋 자체가 실패한다. 예외를 그대로
 * 던져야 Spring이 이 트랜잭션을 롤백하고 커넥션을 정상 상태로 되돌린다 — 호출자({@link
 * ReportAnalysisStatusQueryService#resolveAll})가 자신의 트랜잭션 바깥(= 이 메서드 호출이 끝난 뒤)에서
 * 예외를 잡아야 자기 트랜잭션은 오염되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TerminalFailureJournalReader {

  private final OcrJobFailureViewRepository ocrJobFailureViewRepository;

  /** 확정 실패 저널을 리포트 id별로 묶어 반환한다. 조회 실패 시 예외를 그대로 던진다(위 클래스 javadoc). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, List<OcrJobFailureView>> findTerminalFailures(List<UUID> reportIds) {
    return ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(reportIds).stream()
        .filter(failure -> failure.getReportId() != null)
        .collect(Collectors.groupingBy(OcrJobFailureView::getReportId));
  }
}
