package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 고객이 사정사 평가를 등록했다는 도메인 사실 이벤트. report 컨텍스트는 이 사실만 발행하고, 사정사 프로필의
 * 평점 집계 갱신은 adjuster 컨텍스트 리스너가 맡는다.
 *
 * <p>리스너는 {@code @EventListener}(동기)로 받아 등록과 같은 트랜잭션에서 집계를 갱신한다. 순수 값(VO)이라
 * Spring/JPA를 모른다.
 *
 * @param adjusterId 평가 대상 사정사(users.id)
 * @param reviewId   방금 등록된 평가(adjuster_reviews.id)
 */
public record AdjusterReviewCreatedEvent(UUID adjusterId, UUID reviewId) {
}
