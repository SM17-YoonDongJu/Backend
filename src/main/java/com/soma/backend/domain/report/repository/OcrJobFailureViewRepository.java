package com.soma.backend.domain.report.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.soma.backend.domain.report.entity.OcrJobFailureView;

/**
 * {@code ai.ocr_job_failures} 읽기 전용 리포지토리({@link OcrJobFailureView} 참조 — AI 워커 소유 테이블).
 *
 * <p><b>{@code JpaRepository}가 아니라 {@code Repository}를 확장한 이유:</b> 남의 팀이 소유한 계약 테이블이라
 * Backend는 SELECT만 해야 한다. 베이스 인터페이스를 좁혀 {@code save}·{@code delete}가 타입 레벨에서 아예
 * 존재하지 않게 만든다(엔티티의 {@code @Immutable}과 이중 방어).
 *
 * <p>모든 조회는 {@code terminal = true}(확정 실패)만 읽는다. 일시 실패(terminal=false)는 재전달로 회복될 수
 * 있어 사용자에게 노출하면 안 되므로, 호출자가 조건을 빼먹을 수 없게 <b>메서드 이름에 박아 넣는다</b>
 * (design.md §8 E1).
 */
public interface OcrJobFailureViewRepository
    extends Repository<OcrJobFailureView, UUID>, OcrJobFailureViewRepositoryCustom {

  /**
   * 리포트 묶음의 확정 실패 행을 한 번에 조회한다(목록 화면 배치 조회 — N+1 방지).
   *
   * <p>카드 목록 쿼리에 이 뷰를 조인하지 않는 이유: 리포트 1건에 실패 문서가 N건이면 카드 행이 N배로
   * 복제된다(per-review fan-out과 겹쳐 페이지네이션이 깨진다). 그래서 별도 배치 조회 1회로 붙인다.
   *
   * @param reportIds 조회 대상 리포트 id. 호출자가 빈 컬렉션을 넘기지 않도록 방어한다
   */
  List<OcrJobFailureView> findAllByReportIdInAndTerminalIsTrue(Collection<UUID> reportIds);
}
