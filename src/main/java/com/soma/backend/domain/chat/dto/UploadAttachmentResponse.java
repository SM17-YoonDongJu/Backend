package com.soma.backend.domain.chat.dto;

/**
 * POST /chats/{id}/attachments 응답(설계서 §4 ⑦). 반환된 {@code attachmentKey}를 ③ 전송 요청의
 * {@code attachment}로 넘긴다. private object key만 노출하며 공개 URL은 담지 않는다.
 */
public record UploadAttachmentResponse(
    String attachmentKey,
    String name,
    String contentType,
    long size) {
}
