package com.soma.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // Auth
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "리프레시 토큰을 찾을 수 없습니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // Adjuster
    ADJUSTER_NOT_FOUND(HttpStatus.NOT_FOUND, "손해사정사를 찾을 수 없습니다."),

    // Report
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "리포트를 찾을 수 없습니다."),

    // Matching
    MATCHING_NOT_FOUND(HttpStatus.NOT_FOUND, "매칭 요청을 찾을 수 없습니다."),
    MATCHING_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 진행 중인 매칭이 있습니다."),

    // Payment
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "결제 처리에 실패했습니다."),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "구독 정보를 찾을 수 없습니다."),

    // Chat
    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_NOT_A_MEMBER(HttpStatus.FORBIDDEN, "채팅방 멤버가 아닙니다."),
    CHAT_ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 채팅방 멤버입니다."),
    CHAT_DUPLICATE_DIRECT_ROOM(HttpStatus.CONFLICT, "이미 존재하는 1:1 채팅방입니다."),
    CHAT_DIRECT_JOIN_FORBIDDEN(HttpStatus.FORBIDDEN, "1:1 채팅방에는 입장할 수 없습니다."),
    CHAT_INVALID_MEMBER_COUNT(HttpStatus.BAD_REQUEST, "채팅방 멤버 구성이 올바르지 않습니다."),
    CHAT_MESSAGE_EMPTY(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다."),

    // Chat WebSocket
    CHAT_WS_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "WebSocket 인증에 실패했습니다.");

    private final HttpStatus status;
    private final String message;
}
