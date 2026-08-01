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
 * REPORT_ATTACHMENTS — Report Aggregate에 속한 첨부 진단서 메타데이터(design.md §3). 바이너리는 S3에 저장하고
 * 이 엔티티는 key/URL만 보관한다. pageCount·issuedBy·issuedAt·aiSummary·ocrResultId는 OCR 처리(FastAPI consumer)
 * 이후 채워지는 필드라 생성 시점({@link #of})에는 비워둔다.
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

  @Column(name = "ocr_result_id")
  private UUID ocrResultId;

  /** 리포트 생성 시 문서 1건당 호출되는 정적 팩토리(design.md §3). */
  public static ReportAttachment of(UUID reportId, String name, String url, String mimeType, String reportType) {
    ReportAttachment attachment = new ReportAttachment();
    attachment.reportId = reportId;
    attachment.name = name;
    attachment.url = url;
    attachment.mimeType = mimeType;
    attachment.reportType = reportType;
    return attachment;
  }
}
