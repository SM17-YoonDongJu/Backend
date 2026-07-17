package com.soma.backend.domain.chat.dto;

import java.util.List;

/**
 * POST /chats/{id}/messages 요청(설계서 §4 ③). {@code content}(텍스트/캡션)와 {@code attachments} 중
 * 최소 하나는 있어야 한다. {@code attachments}는 ⑦ 업로드 응답 메타(key 기반)를 그대로 전달하며, 한 메시지에
 * 여러 첨부를 담을 수 있다(파일마다 ⑦을 호출해 확보한 key들을 배열로 전송).
 */
public record SendMessageRequest(String content, List<Attachment> attachments) {

  /** ⑦ 업로드로 확보한 첨부 참조(private object key + 표시 메타). */
  public record Attachment(String attachmentKey, String name, String contentType, Long size) {
  }
}
