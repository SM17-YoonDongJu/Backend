package com.soma.backend.domain.report.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * REPORT_ATTACHMENTS — Report Aggregate 내부 구성요소(첨부 서류 메타). Report를 통해서만 접근한다.
 * 원본 바이너리는 S3에 있고 여기엔 메타·URL만 둔다. ocr_result_id는 상세 응답에 불필요해 매핑을 생략한다
 * (validate는 매핑된 컬럼만 검사한다).
 */
@Entity
@Table(name = "report_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportAttachment extends CreatedAtEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "name", length = 255)
  private String name;

  @Column(name = "mime_type", length = 100)
  private String mimeType;

  @Column(name = "url")
  private String url;

  @Column(name = "report_type", length = 30)
  private String reportType;

  @Column(name = "page_count")
  private Integer pageCount;

  @Column(name = "issued_by", length = 255)
  private String issuedBy;

  @Column(name = "issued_at")
  private LocalDate issuedAt;

  @Column(name = "ai_summary")
  private String aiSummary;
}
