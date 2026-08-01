package com.soma.backend.global.security;

import java.io.IOException;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.ErrorResponse;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

  private static final String AUTH_PATH_PREFIX = "/auth/";
  private static final String OAUTH2_PATH_PREFIX = "/api/v1/auth/oauth2/";

  private final JwtProvider jwtProvider;
  private final JsonMapper jsonMapper;
  private final CookieProvider cookieProvider;
  private final TokenBlacklistRepository tokenBlacklistRepository;

  /**
   * {@code /auth/**} 경로는 access 토큰 검증을 건너뛴다. 재발급·로그아웃 요청에 만료된 access 쿠키가
   * 딸려와도 EXPIRED_TOKEN으로 막히면 안 되기 때문이다(해당 경로는 refresh 쿠키로 동작).
   */
  @Override
  protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
    String path = request.getServletPath();
    return path.startsWith(AUTH_PATH_PREFIX) || path.startsWith(OAUTH2_PATH_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain) throws ServletException, IOException {

    String token = resolveToken(request);
    if (token != null) {
      try {
        jwtProvider.validate(token);
        UUID userId = jwtProvider.getUserId(token);
        if (tokenBlacklistRepository.isBlacklisted(userId)) {
          throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        CustomUserDetails userDetails = new CustomUserDetails(userId, jwtProvider.getRole(token));
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (BusinessException e) {
        sendErrorResponse(response, e);
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  private void sendErrorResponse(HttpServletResponse response, BusinessException ex) throws IOException {
    response.setStatus(ex.getErrorCode().getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(ex.getErrorCode()));
  }

  /**
   * {@code access_token} HttpOnly 쿠키에서만 토큰을 읽는다. 인증은 쿠키 단일 경로로 통일하며
   * {@code Authorization: Bearer} 헤더 인증은 지원하지 않는다(운영·프론트 모두 쿠키 경로).
   */
  private String resolveToken(HttpServletRequest request) {
    return cookieProvider.readCookie(request, CookieProvider.ACCESS_TOKEN_COOKIE)
        .filter(StringUtils::hasText)
        .orElse(null);
  }
}
