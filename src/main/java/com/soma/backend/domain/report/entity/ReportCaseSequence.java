package com.soma.backend.domain.report.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * REPORT_CASE_SEQUENCES — case_no 당일 시퀀스 카운터(day PK, seq). 발급은 ReportRepository의 네이티브
 * ON CONFLICT 쿼리로만 원자 증가하며, 이 엔티티는 스키마 매핑·검증(ddl-auto/validate)용이다(ReportHold 관례).
 */
@Entity
@Table(name = "report_case_sequences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportCaseSequence {

  @Id
  @Column(name = "day")
  private LocalDate day;

  @Column(name = "seq", nullable = false)
  private int seq;
}
