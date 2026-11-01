package com.soma.backend.domain.report.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.NestedRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.ReportJobFailureView;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.PendingAnalysisFailureRow;
import com.soma.backend.domain.report.repository.ReportJobFailureViewRepository;

/**
 * 확정 실패 저널({@code ai.ocr_job_failures}·{@code ai.report_job_failures}) 조회를 별도 트랜잭션
 * (REQUIRES_NEW)으로 격리한다.
 *
 * <p>PostgreSQL은 트랜잭션 안에서 문장 하나가 실패하면 커밋·롤백 전까지 같은 트랜잭션의 이후 모든 문장이
 * "current transaction is aborted"로 연쇄 실패한다. {@link ReportAnalysisResolver#resolveAll}이
 * 같은 트랜잭션 안에서 이 조회를 직접 하고 예외를 잡기만 하면, 저널 조회는 실패해도 그 뒤에 이어지는
 * {@code reportRepository.findAllByIdIn} 호출까지 같은 트랜잭션이라 함께 실패해 버린다(자가 호출이라
 * {@code @Transactional} 재적용도 안 된다 — 별도 Bean으로 빼야 실제로 새 트랜잭션·새 커넥션이 열린다).
 *
 * <p><b>예외를 여기서 삼키지 않는다.</b> 삼기면 Spring이 "메서드가 정상 종료했다"고 보고 이 REQUIRES_NEW
 * 트랜잭션을 커밋하려 드는데, 이미 Postgres 세션이 aborted 상태라 커밋 자체가 실패한다. 예외를 그대로
 * 던져야 Spring이 이 트랜잭션을 롤백하고 커넥션을 정상 상태로 되돌린다 — 호출자({@link
 * ReportAnalysisResolver#resolveAll})가 자신의 트랜잭션 바깥(= 이 메서드 호출이 끝난 뒤)에서
 * 예외를 잡아야 자기 트랜잭션은 오염되지 않는다.
 *
 * <p><b>두 저널을 메서드 단위로 따로 격리한다.</b> 리포트 생성 저널(012)은 AI report 워커가 기동할 때 환경별로
 * 적용하므로, OCR 저널(008)은 있는데 012는 아직 없는 환경이 생긴다. 한 트랜잭션으로 묶으면 012 부재가 OCR
 * 판정까지 같이 무너뜨린다. 메서드마다 프록시를 거치므로 호출할 때마다 각자 새 트랜잭션이 열린다.
 */
@Component
@RequiredArgsConstructor
public class TerminalFailureJournalReader {

  /** PostgreSQL {@code undefined_table}. 저널 테이블이 아직 만들어지지 않은 환경에서 나는 오류다. */
  private static final String UNDEFINED_TABLE = "42P01";

  private final OcrJobFailureViewRepository ocrJobFailureViewRepository;
  private final ReportJobFailureViewRepository reportJobFailureViewRepository;

  /**
   * 조회 실패가 "저널 테이블이 아직 없음"(SQLState 42P01) 때문인지 판별한다. AI 워커가 기동하며 마이그레이션을
   * 적용하기 전의 과도기라 조용히 degrade해도 되는 유일한 원인이다 — 그 밖의 실패(권한 회수·컬럼 변경·커넥션
   * 문제 등)는 테이블이 있는데도 못 읽는 진짜 장애다.
   */
  public static boolean isJournalMissing(NestedRuntimeException ex) {
    return ex.getMostSpecificCause() instanceof SQLException sqlException
        && UNDEFINED_TABLE.equals(sqlException.getSQLState());
  }

  /** OCR 확정 실패 저널을 리포트 id별로 묶어 반환한다. 조회 실패 시 예외를 그대로 던진다(위 클래스 javadoc). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, List<OcrJobFailureView>> findTerminalOcrJobFailures(List<UUID> reportIds) {
    return ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(reportIds).stream()
        .filter(failure -> failure.getReportId() != null)
        .collect(Collectors.groupingBy(OcrJobFailureView::getReportId));
  }

  /**
   * 리포트 생성 확정 실패 저널을 리포트 id별로 묶어 반환한다. 뷰가 terminal·{@code report_id} 존재 행만
   * 노출한다. 조회 실패(012 미적용 등) 시 예외를 그대로 던진다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, List<ReportJobFailureView>> findTerminalReportJobFailures(List<UUID> reportIds) {
    return reportJobFailureViewRepository.findAllByReportIdIn(reportIds).stream()
        .collect(Collectors.groupingBy(ReportJobFailureView::getReportId));
  }

  /**
   * 리포트 생성 확정 실패 중 아직 통지하지 않은 리포트(알림 스윕용). 스윕 트랜잭션과 떼어 두어야 012가 없는
   * 환경에서도 OCR 실패 통지가 계속 나간다. 조회 실패 시 예외를 그대로 던진다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<PendingAnalysisFailureRow> findReportJobFailuresPendingNotification(LocalDateTime since, int limit) {
    return reportJobFailureViewRepository.findPendingNotification(since, limit);
  }
}
