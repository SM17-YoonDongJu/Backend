package com.soma.backend.domain.user.entity.event;

import java.util.UUID;

/**
 * 회원이 탈퇴했다는 도메인 사실 이벤트. user 컨텍스트는 이 사실만 발행하고, 그 회원의 로그인 자격증명을
 * 어떻게 정리할지는 auth 컨텍스트 리스너가 결정한다 — 발행하는 쪽은 auth 내부(자격증명 종류·저장 방식)를
 * 모른다.
 *
 * <p>리스너는 {@code @EventListener}(동기)로 받아 탈퇴와 같은 트랜잭션에서 자격증명을 지운다. 탈퇴가
 * 롤백되면 자격증명도 남아 있어야 하고(계정은 살아 있는데 로그인 수단만 사라지는 상태 금지), 반대로
 * 탈퇴가 커밋되면 자격증명은 반드시 함께 사라져야 하므로 커밋 후로 미루지 않는다. 순수 값(VO)이라
 * Spring/JPA를 모른다.
 *
 * @param userId 탈퇴한 회원(users.id)
 */
public record UserWithdrawnEvent(UUID userId) {
}
