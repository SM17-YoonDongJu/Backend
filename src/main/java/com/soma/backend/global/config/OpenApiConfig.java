package com.soma.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.annotation.PostConstruct;

/**
 * OpenAPI 3 문서 메타데이터 및 access_token 쿠키 인증 스킴 정의.
 * springdoc이 컨트롤러에서 스펙을 생성할 때 이 Bean의 정보를 병합한다. 인증은 HttpOnly 쿠키
 * 단일 경로이므로 Bearer 헤더 스킴이 아니라 apiKey(in cookie) 스킴으로 문서화한다.
 */
@Configuration
public class OpenApiConfig {

  private static final String COOKIE_SCHEME = "cookieAuth";

  /**
   * springdoc(3.0.3)이 스키마 프로퍼티명을 만들 때 쓰는 swagger-core의 정적 싱글턴
   * {@code Json31.mapper()}에 {@code SNAKE_CASE} 네이밍 전략을 반영한다.
   *
   * <p><b>왜 Spring {@code @Bean}이 아니라 이렇게 하는가:</b> springdoc·swagger-core는 Spring
   * 컨텍스트에서 {@code ObjectMapper} 빈을 찾지 않는다 — {@code ModelConverters}가 스키마를 만들 때
   * 항상 {@code new ModelResolver(Json31.mapper())}로 고정해서 쓴다(swagger-core 2.2.47
   * {@code ModelConverters} 소스 확인. 이 앱은 springdoc이 OpenAPI 3.1을 생성하므로 {@code Json.mapper()}가
   * 아니라 {@code Json31.mapper()}가 대상이다). 그래서 Spring Bean으로 {@code ObjectMapper}를 등록해도
   * 무시되고, {@code Json31.mapper()}가 기본값(camelCase, 네이밍 전략 미설정)으로 스키마 프로퍼티명을
   * 만들어 실제 응답(Jackson 3·{@code spring.jackson.property-naming-strategy: SNAKE_CASE}로 만든
   * snake_case)과 어긋났다 — 예: 실제로는 {@code report_id}인데 {@code /v3/api-docs}엔 {@code reportId}.
   *
   * <p>{@code Json31.mapper()}는 지연 초기화되는 정적 싱글턴이라, Spring 컨텍스트 초기화 시점
   * ({@code @PostConstruct})에 한 번 네이밍 전략만 바꿔두면 이후 모든 스키마 생성에 반영된다.
   * 이 매퍼는 문서 생성 전용이고 애플리케이션의 실제 JSON 직렬화는 별개로 Jackson 3
   * ({@code tools.jackson.databind.json.JsonMapper})가 담당하므로, 런타임 응답에는 영향이 없다.
   */
  @PostConstruct
  public void alignSpringdocSchemaNamingWithSnakeCase() {
    Json31.mapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  }

  @Bean
  public OpenAPI brbsOpenAPI() {
    SecurityScheme cookieScheme = new SecurityScheme()
        .type(SecurityScheme.Type.APIKEY)
        .in(SecurityScheme.In.COOKIE)
        .name("access_token")
        .description("로그인 시 서버가 Set-Cookie로 발급하는 HttpOnly access_token 쿠키로 인증한다");

    return new OpenAPI()
        .info(new Info()
            .title("BRBS Backend API")
            .description("보험금 검토·손해사정사 매칭 서비스 API 문서")
            .version("v0.1.0"))
        .components(new Components().addSecuritySchemes(COOKIE_SCHEME, cookieScheme))
        .addSecurityItem(new SecurityRequirement().addList(COOKIE_SCHEME));
  }
}
