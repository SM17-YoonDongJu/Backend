package com.soma.backend.domain.chat.dto;

/**
 * POST /chats/{chatRoomId}/report 요청 바디(snake_case 수신 → camelCase 필드).
 * reason은 필수(ChatReportReason enum 이름), reasonDetail은 선택이되 reason이 OTHER면 필수다.
 *
 * <p>계약상 정해진 에러 code(MISSING_REQUIRED_FIELD·VALIDATION_ERROR)를 내기 위해 reason을 String으로
 * 받고 검증은 ChatReportReason.from()·ChatReport.create()가 수행한다. Bean Validation을 쓰면
 * MethodArgumentNotValidException이 BAD_REQUEST로 고정 매핑되어 계약과 다른 code가 나가고,
 * reason을 enum 타입으로 받으면 Jackson 역직렬화 실패가 INVALID_REQUEST가 되어 역시 계약과 어긋난다.
 */
public record ChatReportRequest(String reason, String reasonDetail) {
}
