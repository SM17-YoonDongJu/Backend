package com.soma.backend.domain.report.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * REPORT_HOLDS — 사정사별 보류 토글(junction). (report_id, adjuster_id) UK.
 */
@Entity
@Table(name = "report_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportHold extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  public ReportHold(UUID reportId, UUID adjusterId) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
  }
}
