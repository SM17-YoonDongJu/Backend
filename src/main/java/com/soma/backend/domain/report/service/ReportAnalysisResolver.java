package com.soma.backend.domain.report.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.OcrResultView;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 리포트 분석 처리 상태를 리포트 묶음 단위로 판정하는 조회 협력 객체. 목록·상세({@code ReportQueryService})와
 * 폴링 단건 조회({@code ReportAnalysisStatusQueryService})가 함께 쓴다 — 판정 규칙 자체는 {@link ReportAnalysis}가
 * 소유하고, 여기는 판정에 필요한 저널·문서 조회와 그 실패 흡수(degrade)만 맡는다.
 *
 * <p>유스케이스 서비스끼리 서로 부르지 않도록 {@code ReportAnalysisStatusQueryService}에서 떼어냈다. 별도 Bean이라
 * {@link #resolveAll}의 {@code REQUIRES_NEW}가 호출 위치와 무관하게 항상 적용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportAnalysisResolver {

  private final ReportRepository reportRepository;
  private final TerminalFailureJournalReader terminalFailureJournalReader;
  private final NeedsReuploadDocumentReader needsReuploadDocumentReader;

  /**
   * 목록·상세 응답에 붙일 분석 상태를 리포트 묶음 단위로 한 번에 판정한다(쿼리 2회 — 실패 뷰 배치 + 리포트 배치).
   * 카드 쿼리에 실패 뷰를 조인하지 않는 이유는 실패 문서 N건당 카드 행이 복제되기 때문이다.
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
   * 없기 때문이다. 호출자(예: {@code ReportQueryService.analysesOrDegrade})의 빈 맵 폴백은 이 메서드 자체가
   * 실패하는(예: 리포트 배치 조회 실패) 더 드문 경우를 위한 마지막 방어선으로만 남는다.
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
    Map<UUID, List<OcrJobFailureView>> failuresByReport = terminalFailuresOrEmpty(targets);
    Map<UUID, List<OcrResultView>> needsReuploadDocumentsByReport = needsReuploadDocumentsOrEmpty(targets);

    Map<UUID, ReportAnalysis> analyses = new HashMap<>();
    for (Report report : reportRepository.findAllByIdIn(targets)) {
      analyses.put(report.getId(), ReportAnalysis.of(report,
          failuresByReport.getOrDefault(report.getId(), List.of()),
          needsReuploadDocumentsByReport.getOrDefault(report.getId(), List.of())));
    }
    return analyses;
  }

  private Map<UUID, List<OcrJobFailureView>> terminalFailuresOrEmpty(List<UUID> targets) {
    try {
      return terminalFailureJournalReader.findTerminalFailures(targets);
    } catch (DataAccessException ex) {
      log.warn("확정 실패 저널 조회 실패 — FAILED 판정만 PROCESSING으로 degrade한다(대상 {}건). "
          + "BLOCKED·NEEDS_REUPLOAD는 reports.status만으로 판정되므로 영향 없다. cause={}",
          targets.size(), ex.getMostSpecificCause().getMessage());
      return Map.of();
    }
  }

  /**
   * 품질 미달 문서 조회 실패는 항상 빈 맵으로 degrade한다(GRANT 미배포면 매번 실패). NEEDS_REUPLOAD 상태
   * 판정 자체는 {@code reports.status}만으로 이미 정확하므로, 이 조회는 문서 특정만 못 할 뿐이다.
   */
  public Map<UUID, List<OcrResultView>> needsReuploadDocumentsOrEmpty(List<UUID> targets) {
    try {
      return needsReuploadDocumentReader.findNeedsReuploadDocuments(targets);
    } catch (DataAccessException ex) {
      log.debug("품질 미달 문서 조회 실패(대상 {}건, GRANT 미배포 시 정상) — 문서 특정 없이 진행한다. cause={}",
          targets.size(), ex.getMostSpecificCause().getMessage());
      return Map.of();
    }
  }
}
