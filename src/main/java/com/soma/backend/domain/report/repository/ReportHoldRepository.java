package com.soma.backend.domain.report.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.ReportHold;

/** ReportHold(junction) Spring Data JPA 리포지토리. 보류 추가는 멱등(재보류 시 사유 갱신). */
public interface ReportHoldRepository extends JpaRepository<ReportHold, UUID> {

  /**
   * 보류 upsert(멱등). UK(adjuster_id, report_id) 충돌 시 최신 사유(reason·reasonDetail)로 갱신하므로
   * 동시 요청·재보류에도 예외 없이 마지막 요청의 사유가 남는다. created_at은 최초 보류 시각을 유지한다.
   * check-then-insert 경쟁으로 인한 DataIntegrityViolation(500)을 원천 차단한다.
   */
  @Modifying
  @Query(value = "INSERT INTO report_holds (id, report_id, adjuster_id, reason, reason_detail, created_at) "
      + "VALUES (gen_random_uuid(), :reportId, :adjusterId, :reason, :reasonDetail, now()) "
      + "ON CONFLICT (report_id, adjuster_id) "
      + "DO UPDATE SET reason = EXCLUDED.reason, reason_detail = EXCLUDED.reason_detail", nativeQuery = true)
  void upsertHold(@Param("reportId") UUID reportId, @Param("adjusterId") UUID adjusterId,
      @Param("reason") String reason, @Param("reasonDetail") String reasonDetail);

  /** 요청 사정사 본인이 해당 report를 보류(hold)했는지 여부(API#6 held 파생 필드). */
  boolean existsByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);
}
