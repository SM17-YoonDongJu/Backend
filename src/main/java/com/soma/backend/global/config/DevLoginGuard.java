package com.soma.backend.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * dev 전용 로그인 백도어가 운영에 노출되는 것을 막는 기동 가드(다층 방어의 마지막 층).
 * {@code app.dev-login.enabled=true}인데 prod 프로파일이 활성이면 애플리케이션 기동을 중단시킨다.
 */
@Component
@ConditionalOnProperty(prefix = "app.dev-login", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevLoginGuard {

  private final Environment environment;

  @PostConstruct
  void assertNotProdProfile() {
    for (String profile : environment.getActiveProfiles()) {
      if ("prod".equalsIgnoreCase(profile)) {
        throw new IllegalStateException(
            "app.dev-login.enabled=true는 prod 프로파일에서 허용되지 않습니다 (dev 로그인 백도어 방지).");
      }
    }
  }
}
