package com.soma.backend.domain.chat.config;

import com.soma.backend.domain.chat.repository.ChatRoomMemberRepository;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtProvider;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * STOMP 프레임 단위 인증·인가 인터셉터.
 * - CONNECT: Authorization STOMP 헤더의 토큰 재검증 후 Principal 바인딩.
 *   핸드셰이크 attribute의 userId를 fallback으로 사용한다.
 * - SUBSCRIBE(/topic/chat/{roomId}): 인증 + 채팅방 멤버십 검증.
 * - SEND: 인증 주체(Principal) 존재 여부 검증.
 * 미인증 프레임은 MessagingException을 던져 거부한다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CHAT_TOPIC_PREFIX = "/topic/chat/";

    private final JwtProvider jwtProvider;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    public StompAuthChannelInterceptor(JwtProvider jwtProvider,
            ChatRoomMemberRepository chatRoomMemberRepository) {
        this.jwtProvider = jwtProvider;
        this.chatRoomMemberRepository = chatRoomMemberRepository;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
        } else if (requiresAuth(command)) {
            if (accessor.getUser() == null) {
                throw new MessagingException(ErrorCode.CHAT_WS_UNAUTHORIZED.getMessage());
            }
            if (StompCommand.SUBSCRIBE.equals(command)) {
                assertChatSubscribeMembership(accessor);
            }
        }

        return message;
    }

    private void assertChatSubscribeMembership(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CHAT_TOPIC_PREFIX)) {
            return;
        }
        String roomIdStr = destination.substring(CHAT_TOPIC_PREFIX.length());
        try {
            UUID roomId = UUID.fromString(roomIdStr);
            UUID userId = getUserIdFromPrincipal(accessor);
            if (userId == null || !chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
                throw new MessagingException(ErrorCode.CHAT_NOT_A_MEMBER.getMessage());
            }
        } catch (IllegalArgumentException e) {
            // roomId가 UUID가 아닌 경우 — 채팅방 구독이 아니므로 통과
        }
    }

    private UUID getUserIdFromPrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        UUID userId = resolveUserId(accessor);
        if (userId == null) {
            throw new MessagingException(ErrorCode.CHAT_WS_UNAUTHORIZED.getMessage());
        }

        String role = resolveRole(accessor, userId);
        CustomUserDetails principal = new CustomUserDetails(userId, role);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        accessor.setUser(authentication);
    }

    private UUID resolveUserId(StompHeaderAccessor accessor) {
        String token = resolveToken(accessor);
        if (token != null && jwtProvider.validateToken(token)) {
            return jwtProvider.getUserId(token);
        }
        Object attr = sessionAttribute(accessor, JwtHandshakeInterceptor.ATTR_USER_ID);
        if (attr instanceof UUID uuid) {
            return uuid;
        }
        return null;
    }

    private String resolveRole(StompHeaderAccessor accessor, UUID userId) {
        String token = resolveToken(accessor);
        if (token != null && jwtProvider.validateToken(token)) {
            return jwtProvider.getRole(token);
        }
        Object attr = sessionAttribute(accessor, JwtHandshakeInterceptor.ATTR_ROLE);
        if (attr instanceof String role) {
            return role;
        }
        throw new MessagingException(ErrorCode.CHAT_WS_UNAUTHORIZED.getMessage());
    }

    private Object sessionAttribute(StompHeaderAccessor accessor, String key) {
        if (accessor.getSessionAttributes() == null) {
            return null;
        }
        return accessor.getSessionAttributes().get(key);
    }

    private boolean requiresAuth(StompCommand command) {
        return StompCommand.SUBSCRIBE.equals(command) || StompCommand.SEND.equals(command);
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
