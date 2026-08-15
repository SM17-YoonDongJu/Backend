package com.soma.backend.domain.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.infra.redis.ChatEventPublisher;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * 상담 채팅방 개설 커맨드 — 고객이 제안의 "상담 수락"을 누르는 시점. ChatRoom Aggregate는 chat 도메인이
 * 소유하므로 그 홈도 여기다 — report 도메인은 이 서비스에 위임할 뿐 ChatRoom 엔티티·리포지토리를 직접
 * 만지지 않는다.
 *
 * <p>이 클래스는 report 패키지를 전혀 import하지 않는다(식별자를 UUID 파라미터로만 받는다). 반대 방향
 * 크로스-도메인 쓰기를 하는 {@link ChatConsultationCommandService}(report_reviews·reports를 직접 조작)와
 * 섞지 않아, 클래스 단위 의존 그래프가 순환하지 않게 유지하기 위함이다.
 *
 * <p>트랜잭션은 호출자(report의 커맨드 유스케이스)의 것을 그대로 쓴다(기본 전파 REQUIRED) —
 * 제안·리포트 상태 전이와 방 개설이 한 커밋으로 묶여야 "상태는 COUNSELING인데 방이 없다"가 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomCommandService {

  private static final String CONSULT_OPENED_SYSTEM_MESSAGE =
      "상담 채팅방이 열렸습니다. 손해사정사와 상담을 시작해보세요.";

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatEventPublisher chatEventPublisher;

  /**
   * 제안에 연결된 상담 방을 멱등하게 확보한다. 이미 있으면 그대로 반환하고(더블클릭·재요청), 없으면
   * 개설 후 SYSTEM 안내 메시지를 남긴다. 동시 요청으로 선조회를 둘 다 통과하면 UNIQUE 제약(V43)이
   * 커밋 시점에 막고 GlobalExceptionHandler가 409 DUPLICATE_RESOURCE로 변환한다.
   */
  @Transactional
  public ConsultationRoomResult openConsultationRoom(
      UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    return chatRoomRepository.findByReportReviewId(reportReviewId)
        .map(existing -> new ConsultationRoomResult(existing.getId(), false))
        .orElseGet(() -> openNewRoom(userId, adjusterId, reportId, reportReviewId));
  }

  private ConsultationRoomResult openNewRoom(
      UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    ChatRoom room = chatRoomRepository.save(
        ChatRoom.openConsultation(userId, adjusterId, reportId, reportReviewId));
    appendSystemMessage(room, CONSULT_OPENED_SYSTEM_MESSAGE);
    return new ConsultationRoomResult(room.getId(), true);
  }

  /** SYSTEM 안내 메시지를 저장·미리보기 갱신하고 커밋 후 브로드캐스트한다. */
  private void appendSystemMessage(ChatRoom room, String text) {
    ChatMessage system = chatMessageRepository.save(ChatMessage.system(room.getId(), text));
    room.touchLastMessage(text, system.getCreatedAt());
    chatEventPublisher.publishAfterCommit(new ChatBroadcastMessage(
        room.getId(), system.getId(), null, ChatMessageType.SYSTEM.name(), text, List.of(),
        system.getCreatedAt()));
  }
}
