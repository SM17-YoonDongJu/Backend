package com.soma.backend.domain.auth.service;

/**
 * 가입 티켓에서 복원한 신원 후보. 아직 회원이 아닌 소셜 계정 정보를 담는다.
 */
public record SignupTicket(String provider, String providerUserId) {
}
