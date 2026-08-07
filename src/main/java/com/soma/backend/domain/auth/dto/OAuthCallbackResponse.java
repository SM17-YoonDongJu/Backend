package com.soma.backend.domain.auth.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OAuth 콜백 결과. 기존 회원이면 쿠키가 발급되고 {@code isNewUser=false},
 * 신규 회원이면 쿠키 없이 {@code signupTicket}이 내려간다.
 */
public record OAuthCallbackResponse(
    @Schema(nullable = true, description = "isNewUser=false일 때만 채워진다") UUID userId,
    boolean isNewUser,
    @Schema(nullable = true, description = "isNewUser=true일 때만 채워진다") String signupTicket) {

  public static OAuthCallbackResponse existingUser(UUID userId) {
    return new OAuthCallbackResponse(userId, false, null);
  }

  public static OAuthCallbackResponse newUser(String signupTicket) {
    return new OAuthCallbackResponse(null, true, signupTicket);
  }
}
