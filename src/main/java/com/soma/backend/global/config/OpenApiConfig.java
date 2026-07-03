package com.soma.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI 3 문서 메타데이터 및 JWT Bearer 인증 스킴 정의.
 * springdoc이 컨트롤러에서 스펙을 생성할 때 이 Bean의 정보를 병합한다.
 */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_SCHEME = "bearerAuth";

  @Bean
  public OpenAPI brbsOpenAPI() {
    SecurityScheme jwtScheme = new SecurityScheme()
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .bearerFormat("JWT")
        .description("Access Token을 'Bearer {token}' 형식으로 전달");

    return new OpenAPI()
        .info(new Info()
            .title("BRBS Backend API")
            .description("보험금 검토·손해사정사 매칭 서비스 API 문서")
            .version("v0.1.0"))
        .components(new Components().addSecuritySchemes(BEARER_SCHEME, jwtScheme))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
  }
}
