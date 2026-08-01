package com.soma.backend.domain.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.global.security.AuthTokenService.RefreshTokenContext;

/**
 * 토큰 재발급 유스케이스. refresh 쿠키를 RTR 검증한 뒤 최신 role로 새 토큰 쌍을 발급한다.
 */
@Service
@RequiredArgsConstructor
public class AuthReissueService {

  private final AuthTokenService authTokenService;
  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public void reissue(HttpServletRequest request, HttpServletResponse response) {
    RefreshTokenContext context = authTokenService.validateRefreshCookie(request);
    User user = userRepository.findById(context.userId())
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    authTokenService.reissueTokens(response, context, user.getRole().name());
  }
}
