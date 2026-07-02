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

  public static ErrorResponse of(HttpStatus httpStatus, String code, String message) {
    return new ErrorResponse(String.valueOf(httpStatus.value()), code, message);
  }
}
