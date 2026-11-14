package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 리포트가 OCR 품질 미달로 재업로드가 필요하다는 도메인 사실 이벤트(NEEDS_REUPLOAD).
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "이 리포트는 문서를 다시 올려야 한다"는 사실만 발행하고, 알림(문안·토글·푸시)으로 만드는 결정은
 * notification 리스너가 한다(Spring/JPA import 0).
 *
 * <p>발행 지점은 {@code ReportNeedsReuploadNotificationSweeper}이며, 수신은 {@code AFTER_COMMIT}이라 알림 발송이
 * 실패해도 이미 커밋된 통지 시각 기록에는 영향이 없다. {@link AnalysisFailedEvent}와 달리 사유(reason)를
 * 안 담는다 — 이 신호는 OCR '실패'가 아니라 '품질 판정'이라 저널 사유({@code AnalysisFailureReason})는 없다.
 * 재업로드 사유는 있지만({@code ai.claim_readiness.reupload_reason}) 이벤트에 싣지 않는다 — 리스너가 조회 응답과 같은
 * 메서드({@code ReportAnalysisResolver#reuploadCausesOrEmpty})로 읽어 문구를 고른다(이벤트 계약·스윕을 바꾸지 않는다).
 *
 * @param userId   수신자(리포트 소유자) — reports.user_id
 * @param reportId 딥링크·컨텍스트용 리포트 식별자
 */
public record ReportNeedsReuploadEvent(UUID userId, UUID reportId) {
}
