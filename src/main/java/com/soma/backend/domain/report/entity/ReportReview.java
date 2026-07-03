package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * REPORT_REVIEWS Aggregate Root — 사정사 검수 작업 공간. (report_id, adjuster_id) 당 1행(UK).
 * AI 초안(REPORTS)과 격리되어 검수 결과만 담는다(design.md A8, glossary §13).
 */
@Entity
@Table(name = "report_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportReview extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Convert(converter = StringListConverter.class)
  @Column(name = "applicable_guarantees")
  private List<String> applicableGuarantees;

  @Convert(converter = StringListConverter.class)
  @Column(name = "omitted_special_contract")
  private List<String> omittedSpecialContract;

  @Column(name = "review")
  private String review;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", length = 30)
  private ReviewOutcome outcome;

  @Column(name = "signed_at")
  private LocalDateTime signedAt;

  @Column(name = "adjuster_license_no", length = 100)
  private String adjusterLicenseNo;

  @Column(name = "adjuster_name", length = 100)
  private String adjusterName;

  public ReportReview(UUID reportId, UUID adjusterId) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
    this.outcome = ReviewOutcome.SENT;
  }

  /** 검수 내용 갱신(RAG 피드백용 review는 서명·상태 전이와 무관). */
  public void updateReviewContent(List<String> applicableGuarantees, List<String> omittedSpecialContract,
      String review) {
    this.applicableGuarantees = applicableGuarantees;
    this.omittedSpecialContract = omittedSpecialContract;
    this.review = review;
  }
}
