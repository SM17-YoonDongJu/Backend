package com.soma.backend.infra.outbox;

import java.util.UUID;

/**
 * {@code AUTH_CLEANUP} 아웃박스 이벤트 페이로드. 회원 탈퇴 후처리(Refresh 삭제·blacklist 등록)에
 * 필요한 데이터를 담는다. JSON으로 직렬화돼 outbox_events.payload에 저장된다.
 */
public record AuthCleanupPayload(UUID userId) {
}
