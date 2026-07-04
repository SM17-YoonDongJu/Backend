package com.soma.backend.domain.report.entity;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * REPORT_ISSUES — Report Aggregate 내부 구성요소(AI 쟁점 초안). Report를 통해서만 접근한다.
 * 검수 결과 컬럼은 없다 — 사정사별 검수 결과는 REPORT_REVIEW_ISSUES로 격리한다.
 */
@Entity
@Table(name = "report_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportIssue extends CreatedAtEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "title")
  private String title;

  @Column(name = "description")
  private String description;

  @Column(name = "ai_status", length = 30)
  private String aiStatus;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "tags", columnDefinition = "text[]")
  private List<String> tags;

  @Column(name = "impact_amount")
  private Long impactAmount;
}
