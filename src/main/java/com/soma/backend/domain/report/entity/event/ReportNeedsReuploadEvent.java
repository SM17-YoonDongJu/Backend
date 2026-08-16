package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 리포트가 OCR 품질 미달로 재업로드가 필요하다는 도메인 사실 이벤트(NEEDS_REUPLOAD).
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "이 리포트는 문서를 다시 올려야 한다"는 사실만 발행하고, 알림(문안·토글·푸시)으로 만드는 결정은
 * notification 리스너가 한다(Spring/JPA import 0).
 *
 * <p>발행 지점은 {@code NeedsReuploadNotificationSweeper}이며, 수신은 {@code AFTER_COMMIT}이라 알림 발송이
 * 실패해도 이미 커밋된 통지 시각 기록에는 영향이 없다. {@link AnalysisFailedEvent}와 달리 사유(reason)를
 * 안 담는다 — 이 신호는 OCR '실패'가 아니라 '품질 판정'이라 {@code ai.ocr_job_failures}(실패 저널)에 행이
 * 남지 않고, 따라서 {@code AnalysisFailureReason}으로 번역할 저널 사유 자체가 존재하지 않는다
 * ({@code ReportAnalysis#needsReupload()}가 문구 하나로 통일하는 것과 같은 이유).
 *
 * @param userId   수신자(리포트 소유자) — reports.user_id
 * @param reportId 딥링크·컨텍스트용 리포트 식별자
 */
public record ReportNeedsReuploadEvent(UUID userId, UUID reportId) {
}
