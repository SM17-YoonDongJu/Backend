package com.soma.backend.report.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.repository.PendingReviewRow;
import com.soma.backend.report.domain.repository.ReportQueryRepository;

/** ReportQueryRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportQueryRepositoryImpl implements ReportQueryRepository {

  private final ReportJpaRepository reportJpaRepository;

  @Override
  public long countPending() {
    return reportJpaRepository.countPending();
  }

  @Override
  public long countDueSoon(LocalDateTime dueSoonThreshold) {
    return reportJpaRepository.countDueSoon(dueSoonThreshold);
  }

  @Override
  public Page<PendingReviewRow> findPendingReviewRows(
      String status, String accidentType, String region, UUID adjusterId, Pageable pageable) {
    return reportJpaRepository.findPendingReviewRows(status, accidentType, region, adjusterId, pageable);
  }
}
