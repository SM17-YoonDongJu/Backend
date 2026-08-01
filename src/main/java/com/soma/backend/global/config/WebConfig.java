package com.soma.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 정적 리소스 뷰 매핑. `/docs` 요청을 Scalar 문서 페이지로 포워딩한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/docs").setViewName("forward:/scalar.html");
  }
}
