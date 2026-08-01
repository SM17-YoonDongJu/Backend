package com.soma.backend.domain.report.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * REPORT_HOLDS — 사정사별 보류(junction). (report_id, adjuster_id) UK. 보류 사유(reason)와
 * 상세 텍스트(reasonDetail)를 함께 담는다. reason이 {@link HoldReason#OTHER}면 reasonDetail이 필수다.
 */
@Entity
@Table(name = "report_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportHold extends CreatedAtEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 30)
  private HoldReason reason;

  @Column(name = "reason_detail")
  private String reasonDetail;

  public ReportHold(UUID reportId, UUID adjusterId, HoldReason reason, String reasonDetail) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }

  /** 재보류 시 최신 사유로 갱신한다(created_at은 최초 보류 시각을 유지). */
  public void updateReason(HoldReason reason, String reasonDetail) {
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }
}
