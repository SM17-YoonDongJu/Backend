package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/** Report 동적 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportRepositoryCustom {

  Page<PendingReviewRow> findPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId, Pageable pageable);

  /**
   * GET /reports 고객 대시보드 카드 목록(design.md §6). userId 소유 리포트를 전 상태로 반환하며,
   * status가 있으면 그 상태만 필터한다. 채택된 제안(report_reviews.status=ACCEPTED)을 LEFT JOIN해
   * 확정 사정사·확정 견적·평점을 붙이고, proposalCount는 REJECTED 제외 상관 서브쿼리로 채운다.
   */
  Page<ReportCardRow> findUserReportCards(UUID userId, ReportStatus status, Pageable pageable);

  /** 리포트 의뢰인의 지역 목록(users.region text[]). 매핑 엔티티 조인 + 배열 컬럼 단일 조회라 QueryDSL fetchOne. */
  List<String> findRegionByReportId(UUID reportId);
}
