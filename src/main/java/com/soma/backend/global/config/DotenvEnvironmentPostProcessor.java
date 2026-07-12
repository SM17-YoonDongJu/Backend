package com.soma.backend.global.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * 로컬 개발 시 프로젝트 루트의 {@code .env} 파일을 Spring Environment에 주입한다.
 *
 * <p>Spring Boot 4에서 {@code EnvironmentPostProcessor}는 {@code META-INF/spring.factories}의
 * {@code org.springframework.boot.EnvironmentPostProcessor} 키로 등록해야 컨텍스트 생성 전
 * (빈 초기화·Flyway보다 먼저) 프로퍼티가 반영된다.
 *
 * <p>우선순위는 시스템 환경변수보다 낮게(addLast) 둔다 — 운영 환경의 실제 환경변수가 항상 이긴다.
 * {@code .env}가 없으면(운영) 조용히 건너뛴다. {@code .env}에 선언된 키만 주입하여
 * OS 환경변수로 Environment가 오염되지 않게 한다.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

  private static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    Dotenv dotenv = Dotenv.configure()
        .ignoreIfMissing()
        .ignoreIfMalformed()
        .load();

    Map<String, Object> properties = new LinkedHashMap<>();
    dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
        .forEach(entry -> properties.put(entry.getKey(), entry.getValue()));

    if (properties.isEmpty()) {
      return;
    }

    environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
  }
}
