package com.soma.backend.domain.auth.dto;

import java.util.UUID;

/**
 * OAuth 콜백 결과. 기존 회원이면 쿠키가 발급되고 {@code isNewUser=false},
 * 신규 회원이면 쿠키 없이 {@code signupTicket}이 내려간다.
 *
 * <p>{@code previouslyWithdrawn}은 이 소셜 신원이 탈퇴 이력이 있는지(재가입인지)를 나타낸다.
 * 신규 가입 경로에서만 의미가 있으며, 프론트가 "탈퇴 후 재가입" 안내를 띄우는 데 쓴다.
 */
public record OAuthCallbackResponse(
    UUID userId, boolean isNewUser, String signupTicket, boolean previouslyWithdrawn) {

  public static OAuthCallbackResponse existingUser(UUID userId) {
    return new OAuthCallbackResponse(userId, false, null, false);
  }

  public static OAuthCallbackResponse newUser(String signupTicket) {
    return new OAuthCallbackResponse(null, true, signupTicket, false);
  }

  public static OAuthCallbackResponse newUser(String signupTicket, boolean previouslyWithdrawn) {
    return new OAuthCallbackResponse(null, true, signupTicket, previouslyWithdrawn);
  }
}
