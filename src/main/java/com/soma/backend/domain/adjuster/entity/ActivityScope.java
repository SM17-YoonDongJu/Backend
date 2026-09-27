package com.soma.backend.domain.adjuster.entity;

import java.util.List;

/**
 * 활동 범위 묶음 — 전문분야(복수)·활동지역·소속 형태. 소속 문자열 해석은 {@link Affiliation#from}이 맡는다.
 *
 * <p>{@link ApplicantProfile}과 마찬가지로 자체 불변식이 없는 파라미터 객체다. 전문분야 목록은 호출자가
 * 넘긴 리스트를 그대로 들고 있는다 — 엔티티의 {@code text[]} 매핑에 그대로 대입되기 때문이다.
 */
public record ActivityScope(List<String> specialties, String region, Affiliation affiliation) {
}
