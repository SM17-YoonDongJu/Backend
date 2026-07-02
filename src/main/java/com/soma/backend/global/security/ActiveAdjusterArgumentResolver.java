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
 * <p>SecurityContext에서 {@link CustomUserDetails}를 꺼내 401/403 가드를 수행하고,
 * 통과 시 활성 사정사의 userId(UUID)를 반환한다.
 */
@Component
public class ActiveAdjusterArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String ACTIVE_ADJUSTER_ROLE = "CERTIFICATED_ADJUSTER";

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
    if (!ACTIVE_ADJUSTER_ROLE.equals(principal.getRole())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
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
