package com.soma.backend.domain.user.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.user.entity.User;

/**
 * 내 정보 응답. 본인 조회이므로 전화번호는 마스킹 없이 원본을 내려준다(수정 화면 prefill용).
 * 이메일은 수집하지 않으므로(스키마에서 phone_number로 대체) 포함하지 않는다. 필드는 Jackson 전역
 * 설정에 따라 snake_case로 직렬화된다(user_id, phone_number, avatar_url, created_at).
 */
public record UserMeResponse(
    UUID userId,
    String nickname,
    @Nullable String phoneNumber,
    String role,
    @Nullable String gender,
    @Nullable List<String> region,
    @Nullable String avatarUrl,
    LocalDateTime createdAt) {

  public static UserMeResponse from(User user) {
    return new UserMeResponse(
        user.getId(),
        user.getNickname(),
        user.getPhoneNumber(),
        user.getRole().name(),
        user.getGender(),
        user.getRegion(),
        user.getAvatarUrl(),
        user.getCreatedAt());
  }
}
