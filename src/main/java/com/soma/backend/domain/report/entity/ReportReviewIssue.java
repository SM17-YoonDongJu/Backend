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
 * REPORT_REVIEW_ISSUES — ReportReview Aggregate 내부 Entity(issue별 검수 결과).
 * report_review_id FK는 소유 Aggregate(ReportReview)의 @OneToMany가 관리한다(자식 직접 저장 금지).
 * reportIssueId는 nullable — null이면 사정사가 신규로 추가한 쟁점(reviewStatus=ADDED)이며 title/description을 담는다.
 */
@Entity
@Table(name = "report_review_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportReviewIssue extends CreatedAtEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_issue_id")
  private UUID reportIssueId;

  @Column(name = "title")
  private String title;

  @Column(name = "description")
  private String description;

  @Column(name = "impact_amount")
  private Long impactAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 30)
  private IssueReviewStatus reviewStatus;

  @Column(name = "adjuster_opinion")
  private String adjusterOpinion;

  @Column(name = "modified_reason")
  private String modifiedReason;

  @Column(name = "excluded_reason")
  private String excludedReason;

  public ReportReviewIssue(UUID reportIssueId, String title, String description, Long impactAmount,
      IssueReviewStatus reviewStatus, String adjusterOpinion, String modifiedReason, String excludedReason) {
    this.reportIssueId = reportIssueId;
    this.title = title;
    this.description = description;
    this.impactAmount = impactAmount;
    this.reviewStatus = reviewStatus;
    this.adjusterOpinion = adjusterOpinion;
    this.modifiedReason = modifiedReason;
    this.excludedReason = excludedReason;
  }

  /** 재검수 시 같은 쟁점(report_issue_id) 행을 삭제·재삽입하지 않고 값만 갱신(부분 UK 충돌 회피). */
  void updateContent(String title, String description, Long impactAmount, IssueReviewStatus reviewStatus,
      String adjusterOpinion, String modifiedReason, String excludedReason) {
    this.title = title;
    this.description = description;
    this.impactAmount = impactAmount;
    this.reviewStatus = reviewStatus;
    this.adjusterOpinion = adjusterOpinion;
    this.modifiedReason = modifiedReason;
    this.excludedReason = excludedReason;
  }
}
