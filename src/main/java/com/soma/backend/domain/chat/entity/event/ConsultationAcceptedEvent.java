package com.soma.backend.domain.chat.entity.event;

import java.util.UUID;

/**
 * 고객이 상담방에서 사정사를 담당으로 확정(수락)했다는 도메인 사실 이벤트. chat 컨텍스트는 이 사실만 발행하고,
 * 사정사 프로필의 상담 완료 수를 어떻게 갱신할지는 adjuster 컨텍스트 리스너가 결정한다.
 *
 * <p>리스너는 {@code @EventListener}(동기)로 받아 수락과 같은 트랜잭션에서 집계를 갱신한다 — 사용자가 응답
 * 직후 갱신된 값을 읽어야 해서 커밋 후로 미루지 않는다. 순수 값(VO)이라 Spring/JPA를 모른다.
 *
 * @param adjusterId 담당으로 확정된 사정사(users.id)
 * @param reportId   수락된 리포트
 */
public record ConsultationAcceptedEvent(UUID adjusterId, UUID reportId) {
}
