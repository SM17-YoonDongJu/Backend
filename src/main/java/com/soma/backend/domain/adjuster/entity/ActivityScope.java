package com.soma.backend.domain.adjuster.entity;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * 활동 범위 묶음 — 전문분야(복수)·활동지역·소속 형태. 소속 문자열 해석은 {@link Affiliation#from}이 맡는다.
 *
 * <p>{@link ApplicantProfile}과 마찬가지로 자체 불변식이 없는 파라미터 객체다. 다만 전문분야 목록은
 * 생성 시점에 불변 리스트로 복사한다 — 이 리스트는 {@code AdjusterApplication}의 {@code text[]} 컬럼에
 * 그대로 대입되므로, 원본을 들고 있으면 호출자가 나중에 원본을 바꿨을 때 신청서 내용이 같이 바뀐다.
 */
public record ActivityScope(List<String> specialties, String region, Affiliation affiliation) {

  public ActivityScope {
    specialties = copyOrNull(specialties);
  }

  private static @Nullable List<String> copyOrNull(@Nullable List<String> values) {
    return values == null ? null : List.copyOf(values);
  }
}
