package com.soma.backend.domain.report.repository;

import java.math.BigDecimal;

/**
 * 홈 헤더·요약 카드용 사정사 비정규화 프로젝션(adjuster_profiles).
 * name = adjuster_profiles.name ?: users.nickname. 누적 검수·상담·평점은 비정규화 컬럼에서 읽는다
 * (증가/집계 책임은 검수 완료·상담·후기 POST 로직 — 미구현 시 null).
 */
public interface AdjusterIdentityRow {

  String getName();

  String getAvatarUrl();

  /** 누적 검수 수(adjuster_profiles.cases_reviewed). */
  Integer getCasesReviewed();

  /** 상담 완료 수(adjuster_profiles.completed_consult_count). */
  Integer getCompletedConsultCount();

  /** 평균 평점(adjuster_profiles.rating_mean). 집계 전이면 null. */
  BigDecimal getRatingMean();

  /** 후기 수(adjuster_profiles.review_count). 집계 전이면 null. */
  Integer getReviewCount();
}
