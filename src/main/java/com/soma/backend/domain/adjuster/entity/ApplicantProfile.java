package com.soma.backend.domain.adjuster.entity;

import org.jspecify.annotations.Nullable;

/**
 * 신청자 인적사항 묶음 — 이름·연락처·경력·소개. 이름 외에는 모두 선택이다(ERD nullable).
 *
 * <p>자체 불변식이 없는 <b>파라미터 객체</b>다. 값 객체로 승격할 만한 규칙이 아직 없어서 일부러 규칙을
 * 넣지 않았고, 생성 인자를 의미 단위로 묶어 순서 혼동을 막는 것이 목적이다.
 */
public record ApplicantProfile(
    String name, @Nullable String phone, @Nullable Integer career, @Nullable String introduction) {
}
