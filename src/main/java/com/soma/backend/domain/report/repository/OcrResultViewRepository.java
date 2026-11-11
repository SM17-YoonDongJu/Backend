package com.soma.backend.domain.report.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.soma.backend.domain.report.entity.OcrResultView;

/**
 * {@code ai.ocr_results} 읽기 전용 리포지토리(AI 워커 소유 테이블, PR #66 GRANT 대상).
 *
 * <p>{@code JpaRepository}가 아니라 {@code Repository}를 확장해 save·delete를 타입 레벨에서 차단한다
 * ({@link OcrJobFailureViewRepository}와 동일 근거 — 남의 팀 계약 테이블은 SELECT만 해야 한다).
 */
public interface OcrResultViewRepository extends Repository<OcrResultView, UUID> {

  /**
   * 리포트 묶음의 품질 판정 문서를 한 번에 조회한다(목록 화면 배치 조회 — N+1 방지).
   *
   * @param reportIds  조회 대상 리포트 id
   * @param ocrQuality 판정 문자열. 호출자는 {@code "needs_reupload"}만 넘긴다
   */
  List<OcrResultView> findAllByReportIdInAndOcrQuality(Collection<UUID> reportIds, String ocrQuality);
}
