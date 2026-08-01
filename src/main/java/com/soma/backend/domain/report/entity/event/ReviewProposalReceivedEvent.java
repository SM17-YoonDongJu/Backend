package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 새 검수 제안(REPORT_REVIEWS 스켈레톤)이 리포트에 도착했다는 도메인 사실 이벤트(RECEIVED_PROPOSAL).
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "새 제안이 생겼다"는 사실만 발행하고, 이 사실을 어떤 알림(문안·토글·푸시)으로 만들지는 notification 리스너가
 * 결정한다 — 그래서 이 record는 NotificationType/문안을 모른다(Spring/JPA import 0).
 *
 * @param userId     수신자(리포트 소유자) — reports.user_id
 * @param reportId   딥링크·컨텍스트용 리포트 식별자
 * @param adjusterId 제안을 보낸 사정사 식별자(향후 문안 확장 여지)
 */
public record ReviewProposalReceivedEvent(UUID userId, UUID reportId, UUID adjusterId) {
}
