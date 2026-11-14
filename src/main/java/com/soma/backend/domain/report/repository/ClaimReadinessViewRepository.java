package com.soma.backend.domain.report.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.soma.backend.domain.report.entity.ClaimReadinessView;

/**
 * {@code ai.claim_readiness} 읽기 전용 리포지토리(AI 워커 소유 계약 테이블, AI 010·013).
 *
 * <p>{@code JpaRepository}가 아니라 {@code Repository}를 확장해 save·delete를 타입 레벨에서 차단한다
 * ({@link OcrResultViewRepository}와 같은 근거 — 남의 팀 계약 테이블은 SELECT만 한다).
 */
public interface ClaimReadinessViewRepository extends Repository<ClaimReadinessView, String> {

  /**
   * 리포트 묶음의 청구 판정 행을 한 번에 조회한다(파생 쿼리, {@code report_id in (...)} — N+1 방지). 계약상 리포트당
   * 최대 1행이지만 {@code report_id}에 UNIQUE가 없어 여러 행이 올 수 있다 — 그 판정은 호출자가 한다.
   *
   * @param reportIds 조회 대상 리포트 id. 호출자는 NEEDS_REUPLOAD 리포트만 넘긴다
   */
  List<ClaimReadinessView> findAllByReportIdIn(Collection<UUID> reportIds);
}
