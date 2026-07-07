package com.soma.backend.global.security;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link ActiveAdjuster} 파라미터 해석기.
 *
 * <p>SecurityContext에서 {@link CustomUserDetails}를 꺼내 userId(UUID)를 주입하는 전용 리졸버다.
 * role 기반 인가(403)는 컨트롤러 메서드의 {@code @PreAuthorize}가 담당한다. principal이 없는 경우는
 * SecurityConfig에서 이미 401로 처리되지만, 방어적으로 {@code LOGIN_REQUIRED}를 던진다.
 */
@Component
public class ActiveAdjusterArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(ActiveAdjuster.class)
        && UUID.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {

    CustomUserDetails principal = extractPrincipal();
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
    }
    return principal.getUserId();
  }

  private CustomUserDetails extractPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return null;
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof CustomUserDetails userDetails) {
      return userDetails;
    }
    return null;
  }
}
