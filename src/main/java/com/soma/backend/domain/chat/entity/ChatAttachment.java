package com.soma.backend.domain.chat.entity;

/**
 * 채팅 메시지 첨부 값 객체(설계서 §4 ③·⑦). private S3 object key + 표시 메타만 보관하며,
 * chatroom_messages.attachments(jsonb) 배열 요소로 저장된다. 엔티티는 presigned URL을 알지 못한다
 * (조회 시 서비스가 key → 단기 presigned GET URL로 변환).
 */
public record ChatAttachment(
    String attachmentKey,
    String name,
    String contentType,
    Integer size) {
}
