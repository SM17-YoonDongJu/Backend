package com.soma.backend.domain.report.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.report.entity.ReportHold;

/** ReportHold(junction) Spring Data JPA 리포지토리. 보류 생성·조회. 멱등 upsert는 서비스가 find-or-create로 처리. */
public interface ReportHoldRepository extends JpaRepository<ReportHold, UUID> {

  Optional<ReportHold> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  /** 요청 사정사 본인이 해당 report를 보류(hold)했는지 여부(API#6 held 파생 필드). */
  boolean existsByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);
}
