package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/** Report 동적 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportRepositoryCustom {

  /**
   * 검수대기 목록(동적 필터 + 페이지네이션). 요청 사정사가 보류(report_holds)한 리포트는 제외하고,
   * specialtyTypes(사정사 전문분야와 매칭되는 accident_type)에 해당하는 건을 상단에 올린 뒤 접수 최신순으로 정렬한다.
   */
  Page<PendingReviewRow> findPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId,
      Set<AccidentType> specialtyTypes, Pageable pageable);

  /**
   * GET /reports 고객 리포트 목록. userId 소유 리포트를 빠짐없이 반환한다 — 리뷰가 있으면 report_reviews
   * 1건당 1행(per-review, 전체 리뷰), 리뷰가 0건인 미검수 리포트도 카드 1행으로 포함되며 이때
   * reviewedAt·adjusterNickname은 null, proposalCount는 0이다. status가 있으면 리포트 상태로 필터한다.
   */
  Page<ReportCardRow> findUserReportCards(UUID userId, ReportStatus status, Pageable pageable);

  /**
   * GET /me/received-proposals 받은 제안 목록(per-review, non-REJECTED). 소유자 리포트에 달린 리뷰 중
   * REJECTED를 제외한 것(제안 받은 건)을 1건당 1행으로 반환한다. 응답 shape는 GET /reports와 동일이다.
   */
  Page<ReportCardRow> findReportsWithProposals(UUID userId, Pageable pageable);

  /** 리포트 의뢰인의 지역 목록(users.region text[]). 매핑 엔티티 조인 + 배열 컬럼 단일 조회라 QueryDSL fetchOne. */
  List<String> findRegionByReportId(UUID reportId);

  /**
   * GET /reports/{reportId} 고객 상세용 크로스-애그리거트 읽기 모델. 채택된 제안(report_reviews.status=ACCEPTED)의
   * 코멘트·확정 시각·확정 배열 3종과 담당 사정사(users.nickname·adjuster_profiles.career)를 한 번에 조회한다.
   * ACCEPTED 제안·담당 사정사가 없으면 해당 필드는 null이다(존재 시 리포트당 ACCEPTED는 최대 1건).
   */
  CustomerReportDetailRow findCustomerReportDetail(UUID reportId);
}
