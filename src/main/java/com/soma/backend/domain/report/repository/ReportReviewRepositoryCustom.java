package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** ReportReview 동적 조회(QueryDSL) 프래그먼트 — 네이티브 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportReviewRepositoryCustom {

  /**
   * API#5 내 검수 내역 목록. 사건별 확정 금액(estimate_min/max)·고객 평점(adjuster_reviews.score, 미작성 null)을
   * 함께 조회한다. status/월 필터, 최근순 정렬, 페이지네이션.
   */
  Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable);

  /**
   * API#5 필터 탭 배지용 — 요청 사정사 본인 검수의 상태별 건수. 월 필터는 {@link #findReviewedReportRows}와 같은
   * 조건을 쓰되 status 탭 필터는 걸지 않는다(어떤 탭이 선택돼도 전체 분포 배지를 보여야 하므로). 경계는 각각 null이면
   * 걸지 않고, 범위는 [monthFrom, monthTo)다. 건수가 0인 상태는 결과에 없다(0 채움은 서비스가 한다).
   */
  List<StatusCount> countByStatusGrouped(UUID adjusterId, LocalDateTime monthFrom, LocalDateTime monthTo);

  /**
   * API#6 제안 목록(design.md §6) — 리포트에 달린 사정사 제안을 최신순으로 조회한다. REJECTED는 노출하지 않는다.
   * rating은 ADJUSTER_PROFILES.rating_mean(scale 2)을 그대로 읽으며, 프로필 행이 없거나 평가가 없으면 null이다.
   */
  Page<ProposalRow> findProposalRows(UUID reportId, Pageable pageable);
}
