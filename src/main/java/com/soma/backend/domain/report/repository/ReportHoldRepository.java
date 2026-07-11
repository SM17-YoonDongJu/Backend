package com.soma.backend.domain.report.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.ReportHold;

/** ReportHold(junction) Spring Data JPA 리포지토리. 보류 추가는 멱등. */
public interface ReportHoldRepository extends JpaRepository<ReportHold, UUID> {

  /**
   * 보류 추가(멱등). UK(adjuster_id, report_id) 충돌 시 DB가 무시하므로 동시 요청에도 예외 없음.
   * check-then-insert 경쟁으로 인한 DataIntegrityViolation(500)을 원천 차단한다.
   */
  @Modifying
  @Query(value = "INSERT INTO report_holds (id, report_id, adjuster_id, created_at) "
      + "VALUES (gen_random_uuid(), :reportId, :adjusterId, now()) "
      + "ON CONFLICT (report_id, adjuster_id) DO NOTHING", nativeQuery = true)
  void insertIfAbsent(@Param("reportId") UUID reportId, @Param("adjusterId") UUID adjusterId);

  /** 요청 사정사 본인이 해당 report를 보류(hold)했는지 여부(API#6 held 파생 필드). */
  boolean existsByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);
}
