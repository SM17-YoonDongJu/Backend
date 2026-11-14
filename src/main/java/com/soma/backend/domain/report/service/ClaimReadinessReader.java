package com.soma.backend.domain.report.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.NestedRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.ClaimReadinessView;
import com.soma.backend.domain.report.repository.ClaimReadinessViewRepository;

/**
 * {@code ai.claim_readiness}(청구 fan-in 판정·재업로드 사유) 배치 조회를 별도 트랜잭션(REQUIRES_NEW)으로 격리한다.
 * 격리 이유와 예외를 여기서 삼키지 않는 이유는 {@link TerminalFailureJournalReader}와 같다 — 자가 호출은 프록시를 타지
 * 않고, 예외를 안에서 삼키면 Spring이 이미 aborted된 Postgres 세션을 커밋하려다 다시 실패한다. 실패 흡수·로그·지표는
 * 호출자({@link ReportAnalysisResolver#reuploadCausesOrEmpty})가 이 호출이 끝난 뒤(이 트랜잭션이 롤백까지 마친 뒤) 한다.
 *
 * <p><b>배포 과도기의 실패.</b> AI 013은 AI 워커가 기동할 때 환경별로 적용되므로 Backend가 먼저 나간 환경에선 컬럼이
 * 없어(42703 — PostgreSQL은 권한 검사보다 컬럼 해석을 먼저 한다), 010조차 없으면 테이블이 없어(42P01) 조회가 실패한다.
 * 013 적용 뒤 컬럼 GRANT만 빠지면 42501이다. {@link #isSchemaMissing}이 앞의 둘(예상된 과도기)만 가른다.
 */
@Component
@RequiredArgsConstructor
public class ClaimReadinessReader {

  /** PostgreSQL {@code undefined_table}(42P01)·{@code undefined_column}(42703) — 010·013 미적용 환경의 오류. */
  private static final Set<String> SCHEMA_MISSING_SQL_STATES = Set.of("42P01", "42703");

  private final ClaimReadinessViewRepository claimReadinessViewRepository;

  /**
   * 조회 실패가 "테이블·컬럼이 아직 없음"(SQLState 42P01·42703) 때문인지 판별한다 — 가장 안쪽 원인이
   * {@link SQLException}일 때 그 SQLState로 본다. AI 마이그레이션 010·013 적용 전의 과도기라 조용히 낮춰도 되는
   * 원인이다. 그 밖의 실패(권한 없음 42501·커넥션 획득 실패 등)는 테이블이 있는데도 못 읽는 장애다.
   *
   * <p>SQLState가 없는 {@link SQLException}도 있다(Hikari 풀 타임아웃의 {@code SQLTransientConnectionException}) —
   * {@code Set.of(...).contains(null)}은 NPE라 먼저 거른다. 거르지 않으면 degrade 처리 중 NPE가 나 목록·상세가 500이
   * 되고 재업로드 푸시가 통째로 빠진다.
   */
  public static boolean isSchemaMissing(NestedRuntimeException ex) {
    return ex.getMostSpecificCause() instanceof SQLException sqlException
        && sqlException.getSQLState() != null
        && SCHEMA_MISSING_SQL_STATES.contains(sqlException.getSQLState());
  }

  /**
   * 청구 판정 행을 리포트 id별로 묶어 반환한다. {@code report_id}가 없는 행은 뺀다. 조회 실패 시 예외를 그대로
   * 던진다(클래스 javadoc).
   *
   * @param reportIds 조회 대상 리포트 id. 호출자는 NEEDS_REUPLOAD 리포트만 넘긴다
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, List<ClaimReadinessView>> findByReportIds(List<UUID> reportIds) {
    return claimReadinessViewRepository.findAllByReportIdIn(reportIds).stream()
        .filter(row -> row.getReportId() != null)
        .collect(Collectors.groupingBy(ClaimReadinessView::getReportId));
  }
}
