package com.soma.backend.global.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.security.ActiveAdjusterArgumentResolver;
import com.soma.backend.global.security.CurrentUserIdArgumentResolver;

/**
 * Spring MVC 전역 설정. 커스텀 ArgumentResolver를 등록한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

  private final ActiveAdjusterArgumentResolver activeAdjusterArgumentResolver;
  private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(activeAdjusterArgumentResolver);
    resolvers.add(currentUserIdArgumentResolver);
  }
}
