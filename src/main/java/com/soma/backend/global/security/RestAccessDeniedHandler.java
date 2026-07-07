package com.soma.backend.global.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.ErrorResponse;

/**
 * 인증됐으나 권한이 부족한 요청에 대해 403 {@code FORBIDDEN} JSON 응답을 반환한다.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private final JsonMapper jsonMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException) throws IOException {
    ErrorCode errorCode = ErrorCode.FORBIDDEN;
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
