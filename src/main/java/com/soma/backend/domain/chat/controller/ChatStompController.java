package com.soma.backend.domain.chat.controller;

import com.soma.backend.domain.chat.dto.ChatRoomActionRequest;
import com.soma.backend.domain.chat.dto.ChatSendRequest;
import com.soma.backend.domain.chat.dto.MessageResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.repository.ChatRoomMemberRepository;
import com.soma.backend.domain.chat.service.ChatMessageService;
import com.soma.backend.domain.chat.service.ChatRoomService;
import com.soma.backend.domain.chat.service.PresenceService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.ErrorResponse;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.infra.fcm.FcmService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

/**
 * STOMP 메시지 컨트롤러.
 * - chat.send: 멤버십은 ChatMessageService에서 검증 → DB 저장 → /topic/chat/{roomId} 브로드캐스트
 *   → 오프라인 멤버에게 FCM 푸시.
 * - chat.join / chat.leave: 시스템 메시지 브로드캐스트.
 * sender_id는 클라이언트 페이로드를 신뢰하지 않고 인증 Principal에서 추출한다.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatStompController {

    @Lazy
    private final ChatMessageService chatMessageService;
    @Lazy
    private final ChatRoomService chatRoomService;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final FcmService fcmService;

    @MessageMapping("chat.send")
    public void sendMessage(@Payload ChatSendRequest request, Principal principal) {
        UUID senderId = extractUserId(principal);
        ChatMessage saved = chatMessageService.saveMessage(
                request.getRoomId(), senderId, request.getContent(), ChatMessageType.TALK);
        messagingTemplate.convertAndSend("/topic/chat/" + request.getRoomId(),
                MessageResponse.from(saved));
        pushOffline(request.getRoomId(), senderId, request.getContent());
    }

    @MessageMapping("chat.join")
    public void joinRoom(@Payload ChatRoomActionRequest request, Principal principal) {
        UUID userId = extractUserId(principal);
        chatRoomService.joinRoom(request.getRoomId(), userId);
        messagingTemplate.convertAndSend("/topic/chat/" + request.getRoomId(),
                MessageResponse.systemMessage(request.getRoomId(), userId,
                        ChatMessageType.JOIN, userId + " 님이 입장했습니다."));
    }

    @MessageMapping("chat.leave")
    public void leaveRoom(@Payload ChatRoomActionRequest request, Principal principal) {
        UUID userId = extractUserId(principal);
        chatRoomService.leaveRoom(request.getRoomId(), userId);
        messagingTemplate.convertAndSend("/topic/chat/" + request.getRoomId(),
                MessageResponse.systemMessage(request.getRoomId(), userId,
                        ChatMessageType.LEAVE, userId + " 님이 퇴장했습니다."));
    }

    @MessageExceptionHandler(BusinessException.class)
    @SendToUser("/queue/errors")
    public ErrorResponse handleBusinessException(BusinessException ex) {
        return ErrorResponse.of(ex.getErrorCode());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ErrorResponse handleException(Exception ex) {
        log.warn("[WS] 처리되지 않은 예외", ex);
        return ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private UUID extractUserId(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUserId();
        }
        throw new BusinessException(ErrorCode.CHAT_WS_UNAUTHORIZED);
    }

    private void pushOffline(UUID roomId, UUID senderId, String content) {
        try {
            chatRoomMemberRepository.findAllByChatRoomId(roomId).stream()
                    .filter(m -> !m.getUserId().equals(senderId))
                    .filter(m -> !presenceService.isOnline(m.getUserId()))
                    .forEach(m -> fcmService.send(m.getUserId(), "새 메시지", content,
                            Map.of("roomId", roomId.toString())));
        } catch (Exception e) {
            log.warn("[FCM push] 실패: roomId={}", roomId, e);
        }
    }
}
