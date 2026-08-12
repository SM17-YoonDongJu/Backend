package com.soma.backend.domain.adjuster.service;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.dto.UpdateAdjusterProfileRequest;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.common.RegionFormat;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 손해사정사 프로필 수정 유스케이스(PATCH /adjusters/me/profile). 프로필 필드(태그라인·소개·경력·활동지역·
 * 전문분야·주요경력)는 ADJUSTER_PROFILES에, 프로필 사진(avatarUrl)은 USERS에 부분 반영한 뒤, 조회 응답과
 * 동일한 flat 프로필을 되돌린다(수정 화면 재바인딩용).
 */
@Service
@RequiredArgsConstructor
public class AdjusterProfileCommandService {

  private final AdjusterProfileRepository adjusterProfileRepository;
  private final UserRepository userRepository;
  private final AdjusterProfileQueryService adjusterProfileQueryService;

  /**
   * 본인 프로필 부분 수정. 값이 있는 필드만 반영하고 {@code null}은 유지한다. activityRegion은 단일 문자열을
   * text[]로 되돌려 저장하고, avatarUrl이 있으면 USERS.avatar_url에 반영한다. 프로필이 없으면
   * {@code ADJUSTER_NOT_FOUND}, avatarUrl 반영 시 사용자가 없으면 {@code USER_NOT_FOUND}. 갱신 후
   * 조회 응답을 재조립해 최신 값(updated_at 포함)을 내린다.
   */
  @Transactional
  public AdjusterProfileResponse updateProfile(UUID userId, UpdateAdjusterProfileRequest request) {
    AdjusterProfile profile = adjusterProfileRepository.findByUserId(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ADJUSTER_NOT_FOUND));

    List<String> activityRegion =
        request.activityRegion() == null ? null : RegionFormat.toList(request.activityRegion());
    profile.updateProfile(
        request.headline(), request.introduction(), request.career(),
        activityRegion, request.specialties(), toCareers(request.careers()));

    if (request.avatarUrl() != null) {
      User user = userRepository.findById(userId)
          .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
      user.updateProfile(null, null, null, request.avatarUrl());
    }

    // @LastModifiedDate(updated_at)까지 반영된 최신 값을 응답에 담기 위해 재조립 전에 flush.
    adjusterProfileRepository.flush();
    return adjusterProfileQueryService.getProfile(userId);
  }

  private static @Nullable List<AdjusterProfile.Career> toCareers(
      @Nullable List<UpdateAdjusterProfileRequest.CareerItem> items) {
    if (items == null) {
      return null;
    }
    return items.stream()
        .map(item -> new AdjusterProfile.Career(item.period(), item.company()))
        .toList();
  }
}
