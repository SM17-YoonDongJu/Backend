package com.soma.backend.global.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        .orElse(ErrorCode.VALIDATION_ERROR.getMessage());
    return ResponseEntity.badRequest()
        .body(ErrorResponse.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message));
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

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError()
        .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
  }
}
