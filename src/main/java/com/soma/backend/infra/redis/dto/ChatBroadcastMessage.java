package com.soma.backend.infra.redis.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Redis pub/sub relay를 통해 인스턴스 간 전달되는 채팅 브로드캐스트 페이로드.
 *
 * <p>전송 서버가 커밋 후 이 메시지를 publish 하면 전 인스턴스가 구독해 STOMP {@code /topic/chat.rooms.{roomId}}로
 * relay 한다. 수신자별로 달라지는 {@code is_mine}은 담지 않는다(클라이언트가 {@code sender_id}로 판별). 첨부는
 * 발급 시점의 단기 presigned GET URL을 담으며(방 참여자 모두 열람 가능) 한 메시지에 여러 개일 수 있다. infra가
 * domain enum에 의존하지 않도록 {@code message_type}은 문자열로 전달한다.
 */
public record ChatBroadcastMessage(
    UUID roomId,
    UUID messageId,
    UUID senderId,
    String messageType,
    String content,
    List<Attachment> attachments,
    LocalDateTime createdAt) {

  /** 첨부 조회용 단기 presigned GET URL + 표시 메타. */
  public record Attachment(String url, String name, String contentType, Long size) {
  }
}
