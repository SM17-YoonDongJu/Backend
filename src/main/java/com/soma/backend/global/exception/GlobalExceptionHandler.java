package com.soma.backend.global.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
    ErrorCode code = ex.getErrorCode();
    return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
        .findFirst()
        .orElse(ErrorCode.BAD_REQUEST.getMessage());
    ErrorCode code = ErrorCode.BAD_REQUEST;
    return ResponseEntity.status(code.getStatus())
        .body(ErrorResponse.of(code.getStatus(), code.name(), message));
  }

  /**
   * 미인증 상태로 메서드 시큐리티(@PreAuthorize) 대상 메서드에 진입하면 인증 공급자가
   * AuthenticationCredentialsNotFoundException(그 상위 AuthenticationException)을 던진다.
   * 필터체인이 permitAll이라 여기까지 전파되므로, 아래 catch-all(Exception → 500)에
   * 삼켜지지 않도록 401 LOGIN_REQUIRED로 매핑한다.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
    return ResponseEntity.status(ErrorCode.LOGIN_REQUIRED.getStatus())
        .body(ErrorResponse.of(ErrorCode.LOGIN_REQUIRED));
  }

  /**
   * 인증됐으나 role이 맞지 않으면 메서드 시큐리티(@PreAuthorize)가 컨트롤러 호출 중
   * AccessDeniedException(그 하위 AuthorizationDeniedException)으로 거부하므로,
   * 아래 catch-all(Exception → 500)에 삼켜지지 않도록 여기서 403 FORBIDDEN으로 매핑한다.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus())
        .body(ErrorResponse.of(ErrorCode.FORBIDDEN));
  }

  /**
   * 요청 바디가 없거나(필수 {@code @RequestBody}) JSON 파싱이 불가능하면 스프링 MVC가
   * HttpMessageNotReadableException을 던진다. 아래 catch-all(Exception → 500)에 삼켜지지 않도록
   * 400 INVALID_REQUEST로 매핑한다.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST));
  }

  /**
   * UNIQUE 제약 위반 등 DB 무결성 충돌을 409로 변환한다. 선검사(existsBy*)와 커밋 사이의 동시성 경쟁으로
   * 커밋 시점에 터지는 중복(예: 전화번호)을 500이 아닌 {@code DUPLICATE_RESOURCE}로 응답한다.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    log.warn("데이터 무결성 위반(유니크 제약 등), 409로 변환", ex);
    ErrorCode code = ErrorCode.DUPLICATE_RESOURCE;
    return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError()
        .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
  }
}
