package com.soma.backend.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.security.CurrentUserIdArgumentResolver;

/**
 * Spring MVC 전역 설정. {@code @CurrentUserId} 커스텀 ArgumentResolver를 등록한다.
 * (ActiveAdjuster 리졸버는 develop OAuth2 전환에서 제거됨 — 권한 검사는 Security/@AuthenticationPrincipal로 이동.)
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(currentUserIdArgumentResolver);
  }
}
