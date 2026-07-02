package com.soma.backend.report.domain.model;

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
 * REPORT_ISSUES — Report Aggregate 내부 구성요소(분쟁 쟁점). Report를 통해서만 접근한다.
 */
@Entity
@Table(name = "report_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportIssue extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "content")
  private String content;
}
