package com.soma.backend.domain.chat.config;

import com.soma.backend.global.security.JwtProvider;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 핸드셰이크 시 JWT를 검증한다.
 * query param ?token= 또는 Authorization 헤더에서 토큰을 추출하고,
 * 검증 실패 시 핸드셰이크를 거부한다(false → 401).
 * 성공 시 session attributes에 userId/role을 저장한다.
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_PARAM = "token";

    static final String ATTR_USER_ID = "userId";
    static final String ATTR_ROLE = "role";

    private final JwtProvider jwtProvider;

    public JwtHandshakeInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String token = resolveToken(request);
        if (token == null || !jwtProvider.validateToken(token)) {
            return false;
        }

        attributes.put(ATTR_USER_ID, jwtProvider.getUserId(token));
        attributes.put(ATTR_ROLE, jwtProvider.getRole(token));
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // no-op
    }

    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String param = servletRequest.getServletRequest().getParameter(TOKEN_PARAM);
            if (StringUtils.hasText(param)) {
                return param;
            }
        }
        return null;
    }
}
