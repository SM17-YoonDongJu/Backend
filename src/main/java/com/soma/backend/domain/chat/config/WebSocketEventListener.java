package com.soma.backend.domain.chat.config;

import com.soma.backend.domain.chat.service.PresenceService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP 세션 생명주기 이벤트 리스너.
 * CONNECT/DISCONNECT 시 PresenceService를 통해 Redis 온라인 상태를 갱신한다.
 * userId는 핸드셰이크 시 저장된 session attribute에서 추출한다.
 */
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final PresenceService presenceService;

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        UUID userId = getUserId(accessor);
        String sessionId = accessor.getSessionId();
        if (userId != null && sessionId != null) {
            presenceService.connect(userId, sessionId);
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        UUID userId = getUserId(accessor);
        String sessionId = accessor.getSessionId();
        if (userId != null && sessionId != null) {
            presenceService.disconnect(userId, sessionId);
        }
    }

    private UUID getUserId(StompHeaderAccessor accessor) {
        if (accessor.getSessionAttributes() == null) {
            return null;
        }
        Object attr = accessor.getSessionAttributes().get(JwtHandshakeInterceptor.ATTR_USER_ID);
        return attr instanceof UUID uuid ? uuid : null;
    }
}
