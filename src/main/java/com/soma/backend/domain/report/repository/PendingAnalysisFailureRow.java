package com.soma.backend.domain.report.repository;

import java.util.UUID;

/**
 * 실패 알림 스윕 대상 리포트 1건(읽기 모델 projection). 알림 수신자를 잡으려면 {@code reports.user_id}가
 * 필요해 실패 뷰 × reports 조인 결과로 받는다.
 *
 * <p>실패 사유·시각은 담지 않는다 — 대표 사유는 해당 리포트의 실패 행을 전부 읽어
 * {@link com.soma.backend.domain.report.entity.ReportAnalysis}가 계산하기 때문이다(배치 경계에서 사유가
 * 잘려 오안내되는 것을 막는다).
 *
 * @param reportId 통지 대상 리포트
 * @param userId   알림 수신자(리포트 소유자)
 */
public record PendingAnalysisFailureRow(UUID reportId, UUID userId) {
}
