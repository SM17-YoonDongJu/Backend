package com.soma.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI 3 문서 메타데이터 및 access_token 쿠키 인증 스킴 정의.
 * springdoc이 컨트롤러에서 스펙을 생성할 때 이 Bean의 정보를 병합한다. 인증은 HttpOnly 쿠키
 * 단일 경로이므로 Bearer 헤더 스킴이 아니라 apiKey(in cookie) 스킴으로 문서화한다.
 */
@Configuration
public class OpenApiConfig {

  private static final String COOKIE_SCHEME = "cookieAuth";

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
