package com.soma.backend.report.application.dto;

import java.util.UUID;

/** API#2 검수 대기 목록 아이템 결과. */
public record PendingReviewItemResult(
    UUID reportId,
    String caseNo,
    String title,
    String accidentType,
    String region,
    String status,
    Long claimedMinAmount,
    Long claimedMaxAmount,
    Long offeredAmount,
    long offerHeadroom,
    long issueCount,
    boolean held) {
}
