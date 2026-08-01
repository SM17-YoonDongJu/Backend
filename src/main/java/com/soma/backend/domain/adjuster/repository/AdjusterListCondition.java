package com.soma.backend.domain.adjuster.repository;

import org.jspecify.annotations.Nullable;

/**
 * 손해사정사 목록 동적 조회 조건(VO). 모두 선택값이며 null이면 해당 필터를 적용하지 않는다.
 * {@code keyword}는 닉네임·헤드라인 부분일치, {@code specialty}는 전문분야 라벨("전체"는 미필터),
 * {@code sort}는 rating·review·career·consultCount 중 하나(미지정·미인식은 평점순).
 * {@code region}은 잠정 계약이라 콤마로 이어진 지역 토큰을 허용하고 activity_region이 그중 하나라도 포함하면 매칭한다.
 */
public record AdjusterListCondition(
    @Nullable String keyword,
    @Nullable String specialty,
    @Nullable String region,
    @Nullable String sort) {
}
