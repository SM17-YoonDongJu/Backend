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

  /** 비동기 처리 접수(202) — 요청은 수락됐고 결과는 이후 별도 파이프라인이 생성한다(예: OCR·AI 리포트). */
  public static <T> ApiResponse<T> accepted(String message, T data) {
    return new ApiResponse<>("202", message, data);
  }
}
