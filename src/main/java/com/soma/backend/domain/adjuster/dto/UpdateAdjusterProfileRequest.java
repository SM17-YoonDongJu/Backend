package com.soma.backend.domain.adjuster.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 손해사정사 프로필 수정 요청(PATCH /adjusters/me/profile). 부분 수정 — 값이 있는 필드만 반영하고
 * {@code null} 필드는 기존 값을 유지한다. 프론트 프로필 편집 폼(headline·introduction·career·
 * activityRegion·avatarUrl·specialties·careers)과 계약을 맞춘다. avatarUrl은 USERS.avatar_url로,
 * 나머지는 ADJUSTER_PROFILES로 반영된다.
 */
public record UpdateAdjusterProfileRequest(
    @Nullable @Size(max = 100, message = "태그라인은 100자 이내여야 합니다.") String headline,
    @Nullable @Size(max = 300, message = "소개는 300자 이내여야 합니다.") String introduction,
    @Nullable @PositiveOrZero(message = "경력 연차는 0 이상이어야 합니다.") Integer career,
    @Nullable @Size(max = 100, message = "활동 지역은 100자 이내여야 합니다.") String activityRegion,
    @Nullable String avatarUrl,
    @Nullable @Size(max = 3, message = "전문분야는 최대 3개까지 선택할 수 있습니다.")
        List<@NotBlank @Size(max = 30) String> specialties,
    @Nullable List<@Valid CareerItem> careers) {

  /** 주요 경력 항목(period·company). 폼 입력값이므로 두 값 모두 필수다. */
  public record CareerItem(
      @NotBlank(message = "경력 기간은 필수입니다.") @Size(max = 100) String period,
      @NotBlank(message = "경력 회사는 필수입니다.") @Size(max = 100) String company) {
  }
}
