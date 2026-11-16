package com.soma.backend.domain.report.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
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
 * "current transaction is aborted"로 연쇄 실패하고, Hibernate는 그 세션을 rollback-only로 표시한다. 그래서 호출자의
 * 트랜잭션 안에서 이 조회를 직접 하고 예외만 잡으면, 저널 조회 뒤에 이어지는 문장까지 함께 실패하거나 커밋 시점에
 * {@code UnexpectedRollbackException}이 터진다(자가 호출이라 {@code @Transactional} 재적용도 안 된다 — 별도 Bean으로
 * 빼야 실제로 새 트랜잭션·새 커넥션이 열린다).
 *
 * <p><b>이 격리가 실제로 지키는 호출자.</b> 바깥 트랜잭션을 가진 분석 실패 알림 스윕(쓰기 트랜잭션)과, 원래 트랜잭션의
 * 자원이 아직 묶여 있는 AFTER_COMMIT 리스너다. 목록·상세·폴링 조회는 #406 이후 바깥 트랜잭션이 없어서, 여기서는 새
 * 트랜잭션 하나를 차례로 쓸 뿐이다(요청 하나가 동시에 쥐는 커넥션 1개 — {@link ReportAnalysisResolver#resolveAll}
 * 참고). 그쪽에 다시 바깥 트랜잭션을 두면 이 REQUIRES_NEW가 바깥 커넥션을 쥔 채 하나를 더 잡는다.
 *
 * <p><b>예외를 여기서 삼키지 않는다.</b> 삼키면 Spring이 "메서드가 정상 종료했다"고 보고 이 REQUIRES_NEW
 * 트랜잭션을 커밋하려 드는데, 이미 Postgres 세션이 aborted 상태라 커밋 자체가 실패한다. 예외를 그대로
 * 던져야 Spring이 이 트랜잭션을 롤백하고 커넥션을 정상 상태로 되돌린다 — 호출자는 이 메서드 호출이 끝난 뒤
 * (= 이 트랜잭션이 롤백까지 마친 뒤) 예외를 잡아 판정을 낮춘다.
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
   *
   * @param excludedReportIds 같은 사이클에 OCR 저널에서 이미 뽑은 리포트(limit 적용 전에 뺀다)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<PendingAnalysisFailureRow> findReportJobFailuresPendingNotification(
      LocalDateTime since, int limit, Collection<UUID> excludedReportIds) {
    return reportJobFailureViewRepository.findPendingNotification(since, limit, excludedReportIds);
  }
}
