package com.soma.backend.global.exception;

public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(String code, String message) {}

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(new ErrorDetail(
                errorCode.getStatus().name(),
                errorCode.getMessage()
        ));
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message));
    }
}
