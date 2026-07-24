package com.soma.backend.domain.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.user.entity.User;
import com.soma.backend.global.common.RegionFormat;

/**
 * 내 정보 응답. 본인 조회이므로 전화번호는 마스킹 없이 원본을 내려준다(수정 화면 prefill용).
 * 이메일은 수집하지 않으므로(스키마에서 phone_number로 대체) 포함하지 않는다. {@code socialProvider}는
 * User 컬럼이 아니라 연결된 SocialAccount(별도 Aggregate)에서 파생해 표시용으로만 내려준다(kakao·naver 등,
 * 미연동이면 {@code null}). 필드는 Jackson 전역 설정에 따라 snake_case로 직렬화된다
 * (user_id, phone_number, avatar_url, social_provider, created_at).
 */
public record UserMeResponse(
    UUID userId,
    String nickname,
    @Nullable String phoneNumber,
    String role,
    @Nullable String gender,
    String region,
    @Nullable String avatarUrl,
    @Nullable String socialProvider,
    LocalDateTime createdAt) {

  public static UserMeResponse from(User user, @Nullable String socialProvider) {
    return new UserMeResponse(
        user.getId(),
        user.getNickname(),
        user.getPhoneNumber(),
        user.getRole().name(),
        user.getGender(),
        RegionFormat.toSingle(user.getRegion()),
        user.getAvatarUrl(),
        socialProvider,
        user.getCreatedAt());
  }
}
