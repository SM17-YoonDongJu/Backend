package com.soma.backend.domain.adjuster.repository;

/**
 * 손해사정사 목록 상단 통계 밴드(StatsBand) 집계 프로젝션. 검색 필터와 무관한 인증(CERTIFICATED) 사정사 모수 지표다
 * — 인증 사정사 수·평균 평점(rating_mean 보유 프로필 평균)·누적 상담 합·평균 경력. 빈 모수면 0으로 coalesce한다.
 */
public record AdjusterListMetaRow(
    long totalAdjusterCount,
    double averageRating,
    long totalConsultCount,
    int averageCareer) {
}
