package com.soma.backend.domain.chat.dto;

/**
 * POST /chats/{id}/messages 요청(설계서 §4 ③). {@code content}(텍스트/캡션)와 {@code attachment} 중
 * 최소 하나는 있어야 한다. {@code attachment}는 ⑦ 업로드 응답 메타를 그대로 전달한다.
 */
public record SendMessageRequest(String content, Attachment attachment) {

  /** ⑦ 업로드로 확보한 첨부 참조(private object key + 표시 메타). */
  public record Attachment(String attachmentKey, String name, String contentType) {
  }
}
