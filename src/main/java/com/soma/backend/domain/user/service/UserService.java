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
import com.soma.backend.infra.outbox.OutboxEventPublisher;

/**
 * 내 정보 조회·수정·탈퇴 유스케이스.
 *
 * <p>모든 동작은 {@code CustomUserDetails.userId}(본인)만 대상으로 한다. 탈퇴는 soft delete(WITHDRAWN)
 * + 개인정보 익명화 + 소셜 언링크를 트랜잭션에서 수행하고, Redis 부수효과는 아웃박스로 위임한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final SocialAccountRepository socialAccountRepository;
  private final AuthTokenService authTokenService;
  private final OutboxEventPublisher outboxEventPublisher;

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
   * 회원 탈퇴. 계정을 익명화(WITHDRAWN)하고 소셜 링크를 끊은 뒤, 세션 무효화 부수효과(Refresh 삭제·access
   * blacklist)는 아웃박스에 적재해 커밋 이후 소비자가 멱등하게 처리·재시도하도록 위임한다
   * (dual-write 불일치 제거). 쿠키 만료는 HTTP 응답이라 요청 스레드에서 즉시 처리한다.
   */
  @Transactional
  public void withdraw(UUID userId, HttpServletResponse response) {
    User user = findActiveUser(userId);
    user.withdraw();
    socialAccountRepository.deleteByUserId(userId);
    outboxEventPublisher.publishAuthCleanup(userId);
    authTokenService.expireCookies(response);
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
