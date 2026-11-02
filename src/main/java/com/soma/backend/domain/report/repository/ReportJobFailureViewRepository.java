package com.soma.backend.domain.report.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.soma.backend.domain.report.entity.ReportJobFailureView;

/**
 * {@code ai.report_job_failures} 읽기 전용 리포지토리({@link ReportJobFailureView} 참조 — AI 워커 소유 테이블).
 *
 * <p>{@code JpaRepository}가 아니라 {@code Repository}를 확장해 {@code save}·{@code delete}가 타입 레벨에서
 * 아예 없게 한다({@code OcrJobFailureViewRepository}와 같은 이유 — 엔티티의 {@code @Immutable}과 이중 방어).
 *
 * <p>메서드 이름에 {@code TerminalIsTrue}가 없는 것은 뷰 자체가 terminal 행만 노출하기 때문이다(서브셀렉트의
 * where). 호출자가 terminal 조건을 빠뜨릴 방법이 없다.
 */
public interface ReportJobFailureViewRepository
    extends Repository<ReportJobFailureView, UUID>, ReportJobFailureViewRepositoryCustom {

  /**
   * 리포트 묶음의 리포트 생성 확정 실패 행을 한 번에 조회한다(목록 화면 배치 조회 — N+1 방지).
   *
   * @param reportIds 조회 대상 리포트 id. 호출자가 빈 컬렉션을 넘기지 않도록 방어한다
   */
  List<ReportJobFailureView> findAllByReportIdIn(Collection<UUID> reportIds);
}
