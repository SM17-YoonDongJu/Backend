package com.soma.backend.report.domain.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 검수 대기 목록·요약 조회 전용 포트(CQRS). Aggregate 로딩을 우회해 파생 필드를 한 쿼리로 계산한다(ddd-tactical §4).
 */
public interface ReportQueryRepository {

  long countPending();

  long countDueSoon(LocalDateTime dueSoonThreshold);

  Page<PendingReviewRow> findPendingReviewRows(
      String status, String accidentType, String region, UUID adjusterId, Pageable pageable);
}
