package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Collection;
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
   * REJECTED를 제외한 것(제안 받은 건)을 1건당 1행으로 반환
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

  /**
   * 검수 화면 좌측 맥락(의뢰인·사고·상품). 리포트가 없으면 null, 청구·상품·보험사가 없으면 해당 필드만 null이다.
   * 보험사·상품은 읽기 전용 엔티티({@code Insurer}·{@code InsuranceProduct})로 매핑해 엔티티 조인으로 붙인다.
   */
  ReviewContextRow findReviewContext(UUID reportId);

  /**
   * 검수 대기 풀(AWAITING_INSPECTION + AWAITING_ADOPTION) 중 요청 사정사의 '내 대기 큐' 건수 — 본인이
   * 보류하지도, 이미 검수 진행(SENT·COUNSELING)하지도 않은 리포트. 사정사별 개인화 카운트라 홈 풀(전역 정의)과
   * 값이 다를 수 있다.
   */
  long countPendingNotHeldBy(UUID adjusterId);

  /** 마감 임박(AWAITING_INSPECTION·threshold 이전 접수) 중 '내 대기 큐'에 남은 건수. */
  long countDueSoonNotHeldBy(LocalDateTime dueSoonThreshold, UUID adjusterId);

  /**
   * 검수 대기 풀 중 지정 사고유형(사정사 전문분야 매칭)에 해당하는 '내 대기 큐' 건수. 요약의 '내 전문분야 매칭'
   * 배지도 검수 대기와 같은 개인화 축(보류·내 검수 제외)에 맞춘다. types가 비면 호출자가 0으로 처리한다.
   */
  long countPendingByAccidentTypeInNotHeldBy(Collection<AccidentType> types, UUID adjusterId);
}
