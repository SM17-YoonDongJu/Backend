package com.soma.backend.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GlobalExceptionHandler의 Spring MVC 프레임워크 예외 → 상태코드 매핑 회귀 테스트. catch-all(Exception → 500)이
 * 4xx 프레임워크 예외를 삼켜 500으로 내보내던 dev 500 스톰(HttpRequestMethodNotSupportedException)을 방지한다.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new PostOnlyController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("POST 전용 경로를 GET으로 호출하면 catch-all(500)에 삼켜지지 않고 405 METHOD_NOT_ALLOWED로 응답한다")
  void getOnPostOnlyEndpoint_returns405_notSwallowedAs500() throws Exception {
    mockMvc.perform(get("/post-only"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.status").value(String.valueOf(HttpStatus.METHOD_NOT_ALLOWED.value())))
        .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.name()));
  }

  @RestController
  static class PostOnlyController {

    @PostMapping("/post-only")
    String create() {
      return "ok";
    }
  }
}
