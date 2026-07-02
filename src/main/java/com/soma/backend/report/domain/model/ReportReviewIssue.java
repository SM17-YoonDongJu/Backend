package com.soma.backend.report.domain.model;

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

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * REPORT_REVIEW_ISSUES — ReportReview Aggregate 하위, issue별 검수 결과.
 * 경쟁 검수 격리(사정사마다 별도 행)를 위해 REPORT_ISSUES를 직접 수정하지 않는다.
 */
@Entity
@Table(name = "report_review_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportReviewIssue extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_review_id", nullable = false)
  private UUID reportReviewId;

  @Column(name = "report_issue_id", nullable = false)
  private UUID reportIssueId;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 30)
  private IssueReviewStatus reviewStatus;

  @Column(name = "adjuster_opinion")
  private String adjusterOpinion;

  @Column(name = "modified_reason")
  private String modifiedReason;

  @Column(name = "excluded_reason")
  private String excludedReason;

  public ReportReviewIssue(UUID reportReviewId, UUID reportIssueId, IssueReviewStatus reviewStatus,
      String adjusterOpinion, String modifiedReason, String excludedReason) {
    this.reportReviewId = reportReviewId;
    this.reportIssueId = reportIssueId;
    this.reviewStatus = reviewStatus;
    this.adjusterOpinion = adjusterOpinion;
    this.modifiedReason = modifiedReason;
    this.excludedReason = excludedReason;
  }
}
