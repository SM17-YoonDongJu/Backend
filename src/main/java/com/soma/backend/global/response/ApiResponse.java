package com.soma.backend.global.response;

public record ApiResponse<T>(String status, String message, T data) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>("200", "정상 처리되었습니다.", data);
  }

  public static <T> ApiResponse<T> ok(String message, T data) {
    return new ApiResponse<>("200", message, data);
  }

  public static ApiResponse<Void> ok() {
    return new ApiResponse<>("200", "정상 처리되었습니다.", null);
  }
}
