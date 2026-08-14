package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

import com.soma.backend.domain.report.entity.AnalysisFailureReason;

/**
 * 리포트 분석(OCR·AI)이 확정 실패했다는 도메인 사실 이벤트(ANALYSIS_FAILED).
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "이 리포트의 분석이 확정 실패했다"는 사실만 발행하고, 이 사실을 어떤 알림(문안·토글·푸시)으로 만들지는
 * notification 리스너가 결정한다 — 그래서 이 record는 NotificationType·문안을 모른다(Spring/JPA import 0).
 *
 * <p>발행 지점은 실패 알림 스윕({@code AnalysisFailureNotificationSweeper})이며, 수신은
 * {@code AFTER_COMMIT}이라 알림 발송이 실패해도 이미 커밋된 통지 시각 기록에는 영향이 없다.
 *
 * @param userId   수신자(리포트 소유자) — reports.user_id
 * @param reportId 딥링크·컨텍스트용 리포트 식별자
 * @param reason   대표 실패 사유. 알림 문안 분기(재업로드 안내 여부)에 쓴다
 */
public record AnalysisFailedEvent(UUID userId, UUID reportId, AnalysisFailureReason reason) {
}
