package com.soma.backend.domain.user.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.user.dto.UserMeResponse;
import com.soma.backend.domain.user.dto.UserUpdateRequest;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.infra.redis.TokenBlacklistRepository;
import com.soma.backend.infra.redis.WithdrawalLedgerRepository;

/**
 * 내 정보 조회·수정·탈퇴 유스케이스.
 *
 * <p>모든 동작은 {@code CustomUserDetails.userId}(본인)만 대상으로 한다. 탈퇴는 soft delete(WITHDRAWN)
 * + 개인정보 익명화 + 소셜 언링크 + 세션 무효화(refresh 삭제·access blacklist·쿠키 만료)를 함께 수행한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final SocialAccountRepository socialAccountRepository;
  private final AuthTokenService authTokenService;
  private final TokenBlacklistRepository tokenBlacklistRepository;
  private final WithdrawalLedgerRepository withdrawalLedgerRepository;

  /**
   * 내 정보를 조회한다. 존재하지 않거나 이미 탈퇴한 계정이면 {@code USER_NOT_FOUND}.
   */
  @Transactional(readOnly = true)
  public UserMeResponse getMe(UUID userId) {
    return UserMeResponse.from(findActiveUser(userId));
  }

  /**
   * 전화번호·지역·프로필사진을 부분 수정한다. 넘어온 값이 하나도 없으면 {@code INVALID_REQUEST},
   * 다른 회원이 쓰는 번호로 바꾸려 하면 {@code DUPLICATE_RESOURCE}. 번호가 실제로 바뀌면 인증 상태가
   * 초기화된다(엔티티 규칙).
   */
  @Transactional
  public UserMeResponse updateMe(UUID userId, UserUpdateRequest request) {
    if (request.hasNoField()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    User user = findActiveUser(userId);
    String newPhone = request.phoneNumber();
    if (newPhone != null
        && !newPhone.equals(user.getPhoneNumber())
        && userRepository.existsByPhoneNumber(newPhone)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    user.updateProfile(newPhone, request.region(), request.avatarUrl());
    return UserMeResponse.from(user);
  }

  /**
   * 회원 탈퇴. 탈퇴 전 소셜 신원을 원장에 기록(재가입 인지용)한 뒤, 계정을 익명화(WITHDRAWN)하고
   * 소셜 링크를 끊는다. 이어서 세션을 무효화한다 — refresh 삭제 + 쿠키 만료({@link AuthTokenService})
   * 및 살아있는 access 토큰 blacklist 등록.
   */
  @Transactional
  public void withdraw(UUID userId, HttpServletResponse response) {
    User user = findActiveUser(userId);
    socialAccountRepository.findByUserId(userId)
        .forEach(social ->
            withdrawalLedgerRepository.record(social.getProvider(), social.getProviderUserId()));
    user.withdraw();
    socialAccountRepository.deleteByUserId(userId);

    authTokenService.clearTokens(response, userId);
    tokenBlacklistRepository.blacklist(userId);
  }

  private User findActiveUser(UUID userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    if (user.isWithdrawn()) {
      throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }
    return user;
  }
}
