package com.soma.backend.global.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtFilter jwtFilter;
  private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
  private final RestAccessDeniedHandler restAccessDeniedHandler;

  /**
   * CORS 허용 오리진 패턴. 쿠키 인증(allowCredentials)이라 와일드카드 {@code *}는 쓸 수 없어
   * 패턴 목록으로 관리한다. 로컬 기본값은 {@code http://localhost:3000}이며, 운영/프리뷰 오리진은
   * {@code CORS_ALLOWED_ORIGIN_PATTERNS} 환경변수로 주입한다(예: {@code https://앱도메인,https://*.vercel.app}).
   */
  @Value("${app.cors.allowed-origin-patterns}")
  private List<String> allowedOriginPatterns;

  /**
   * 개발 환경에서 API 문서(Scalar)를 인증 없이 열지 여부. 기본 {@code false}(운영 보호)이며,
   * {@code application-dev.yml}에서만 {@code true}로 개방해 {@code /docs}·{@code /scalar.html}·
   * {@code /v3/api-docs}를 permitAll 한다. 프로파일 하드코딩 대신 설정값으로 제어한다.
   */
  @Value("${app.docs.public:false}")
  private boolean docsPublic;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(AbstractHttpConfigurer::disable)
        // 익명 인증 비활성화: 미인증 요청의 SecurityContext 인증을 null로 둬,
        // @PreAuthorize 대상 메서드 진입 시 AuthenticationCredentialsNotFoundException(→ 401)이
        // 나오게 한다(익명 토큰이면 role 불일치로 403이 나 401/403 구분이 무너진다).
        .anonymous(AbstractHttpConfigurer::disable)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> {
          auth.requestMatchers("/auth/**", "/ws/**", "/ws-chat/**", "/actuator/health", "/actuator/health/**")
              .permitAll();
          if (docsPublic) {
            // dev 전용: Scalar API 문서 경로를 인증 없이 개방(app.docs.public=true, 운영은 기본 false).
            auth.requestMatchers("/docs", "/scalar.html", "/v3/api-docs", "/v3/api-docs/**").permitAll();
          }
          auth.anyRequest().authenticated();
        })
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(restAuthenticationEntryPoint)
            .accessDeniedHandler(restAccessDeniedHandler))
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  /**
   * 쿠키 기반 인증용 CORS 설정. {@code allowCredentials(true)}로 브라우저가 HttpOnly 쿠키를
   * 주고받도록 허용하며, 자격증명과 함께 쓸 수 없는 와일드카드 대신 {@code allowedOriginPatterns}로
   * 정확 오리진과 Vercel 프리뷰 패턴({@code *.vercel.app})을 함께 허용한다.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(allowedOriginPatterns);
    config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
