package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 리포트가 AI 입력 가드레일에 차단됐다는 도메인 사실 이벤트(REPORT_BLOCKED).
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "이 리포트가 차단됐다"는 사실만 발행하고, 알림(문안·토글·푸시)으로 만드는 결정은 notification 리스너가
 * 한다(Spring/JPA import 0).
 *
 * <p>발행 지점은 {@code BlockedReportNotificationSweeper}이며, 수신은 {@code AFTER_COMMIT}이라 알림 발송이
 * 실패해도 이미 커밋된 통지 시각 기록에는 영향이 없다. {@link AnalysisFailedEvent}와 달리 사유(reason)를
 * 안 담는다 — AI가 차단 사유(오프토픽·PII 복호화 실패 등)를 reports 테이블로 넘기지 않아 Backend가 구분할
 * 수단이 없다({@code ReportAnalysis#blocked()}가 중립 문구 하나로 통일하는 것과 같은 이유).
 *
 * @param userId   수신자(리포트 소유자) — reports.user_id
 * @param reportId 딥링크·컨텍스트용 리포트 식별자
 */
public record ReportBlockedEvent(UUID userId, UUID reportId) {
}
