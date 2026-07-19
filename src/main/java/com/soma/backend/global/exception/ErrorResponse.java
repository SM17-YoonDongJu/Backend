package com.soma.backend.global.exception;

import org.springframework.http.HttpStatus;

public record ErrorResponse(String status, String code, String message) {

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(
        String.valueOf(errorCode.getStatus().value()),
        errorCode.name(),
        errorCode.getMessage()
    );
  }

  /** code·status는 enum에서 취하고 message만 동적으로 덮어쓴다(예: @Valid 필드 메시지). */
  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(
        String.valueOf(errorCode.getStatus().value()),
        errorCode.name(),
        message
    );
  }

  public static ErrorResponse of(HttpStatus httpStatus, String code, String message) {
    return new ErrorResponse(String.valueOf(httpStatus.value()), code, message);
  }
}
