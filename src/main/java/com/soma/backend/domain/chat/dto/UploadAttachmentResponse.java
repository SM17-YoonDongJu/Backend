package com.soma.backend.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * POST /chats/{id}/attachments 응답(설계서 §4 ⑦). 반환된 {@code attachmentKey}를 ③ 전송 요청의
 * {@code attachment}로 넘긴다. private object key만 노출하며 공개 URL은 담지 않는다.
 */
public record UploadAttachmentResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String attachmentKey,
    @Schema(nullable = true, description = "업로드 클라이언트가 원본 파일명을 보내지 않으면 null일 수 있다") String name,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long size) {
}
