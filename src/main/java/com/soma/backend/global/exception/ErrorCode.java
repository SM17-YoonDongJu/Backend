package com.soma.backend.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  // 400 Bad Request — 요청 자체가 잘못됨
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값 검증에 실패했습니다."),
  MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST, "필수 입력값이 누락되었습니다."),
  UNSUPPORTED_OPERATION(HttpStatus.BAD_REQUEST, "지원하지 않는 동작입니다."),

  // 401 Unauthorized — 인증 실패
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
  EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),

  // 403 Forbidden — 권한 없음
  FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

  // 404 Not Found — 리소스 없음
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 게시물/리포트를 찾을 수 없습니다."),
  SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "구독 정보를 찾을 수 없습니다."),

  // 409 Conflict — 리소스 상태 충돌
  DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),

  // 422 Unprocessable Content — 비즈니스 규칙 위반
  PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "결제 처리에 실패했습니다."),

  // 500 Internal Server Error — 서버 내부 오류
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
  DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "데이터베이스 오류가 발생했습니다."),
  EXTERNAL_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "외부 API 연동에 실패했습니다."),

  // 503 Service Unavailable — 일시적 서비스 불가
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 이용할 수 없습니다."),

  // Auth (도메인 특화)
  REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "리프레시 토큰을 찾을 수 없습니다."),

  // Adjuster (도메인 특화)
  ADJUSTER_NOT_FOUND(HttpStatus.NOT_FOUND, "손해사정사를 찾을 수 없습니다."),

  // Report (도메인 특화)
  REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."),
  REPORT_ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트 쟁점을 찾을 수 없습니다."),
  INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 상태 전이입니다."),

  // Matching (도메인 특화)
  MATCHING_NOT_FOUND(HttpStatus.NOT_FOUND, "매칭 요청을 찾을 수 없습니다."),
  MATCHING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 매칭이 있습니다."),

  // Chat (도메인 특화)
  CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
  CHAT_NOT_A_MEMBER(HttpStatus.FORBIDDEN, "채팅방 멤버가 아닙니다."),
  CHAT_ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 채팅방 멤버입니다."),
  CHAT_DUPLICATE_DIRECT_ROOM(HttpStatus.CONFLICT, "이미 존재하는 1:1 채팅방입니다."),
  CHAT_DIRECT_JOIN_FORBIDDEN(HttpStatus.FORBIDDEN, "1:1 채팅방에는 입장할 수 없습니다."),
  CHAT_INVALID_MEMBER_COUNT(HttpStatus.BAD_REQUEST, "채팅방 멤버 구성이 올바르지 않습니다."),
  CHAT_MESSAGE_EMPTY(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다."),
  CHAT_WS_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "WebSocket 인증에 실패했습니다.");

  private final HttpStatus status;
  private final String message;
}
