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

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * REPORT_ISSUES_REVIEWS — ReportReview Aggregate 내부 Entity(issue별 검수 결과).
 * report_review_id FK는 소유 Aggregate(ReportReview)의 @OneToMany가 관리한다(자식 직접 저장 금지).
 * reportIssueId는 nullable — null이면 사정사가 신규로 추가한 쟁점(reviewStatus=ADDED)이며 title/description을 담는다.
 */
@Entity
@Table(name = "report_issues_reviews")
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
  private Integer impactAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_status", nullable = false, length = 30)
  private IssueReviewStatus reviewStatus;

  @Column(name = "adjuster_opinion")
  private String adjusterOpinion;

  @Column(name = "modified_reason")
  private String modifiedReason;

  @Column(name = "excluded_reason")
  private String excludedReason;

  private ReportReviewIssue(UUID reportIssueId, String title, String description, Integer impactAmount,
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

  /**
   * 쟁점 검수 1건을 만든다. 검수 상태별 필수값을 여기서 강제한다 — 추가(ADDED)는 제목·설명, 수정(MODIFIED)은
   * 수정 사유, 제외(EXCLUDED)는 제외 사유가 비면 MISSING_REQUIRED_FIELD. 기존 AI 쟁점이 실제로 있는지는
   * 리포지토리 조회가 필요해 호출 서비스가 이 팩토리보다 먼저 확인한다.
   */
  public static ReportReviewIssue create(UUID reportIssueId, String title, String description, Integer impactAmount,
      IssueReviewStatus reviewStatus, String adjusterOpinion, String modifiedReason, String excludedReason) {
    boolean missingRequired = switch (reviewStatus) {
      case ADDED -> isBlank(title) || isBlank(description);
      case MODIFIED -> isBlank(modifiedReason);
      case EXCLUDED -> isBlank(excludedReason);
      default -> false;
    };
    if (missingRequired) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    return new ReportReviewIssue(reportIssueId, title, description, impactAmount, reviewStatus, adjusterOpinion,
        modifiedReason, excludedReason);
  }

  /** 사정사가 제외(EXCLUDED)한 쟁점인지 — 공유 리포트 화면에서 숨긴다. */
  public boolean isExcluded() {
    return this.reviewStatus == IssueReviewStatus.EXCLUDED;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** 재검수 시 같은 쟁점(report_issue_id) 행을 삭제·재삽입하지 않고 값만 갱신(부분 UK 충돌 회피). */
  void updateContent(String title, String description, Integer impactAmount, IssueReviewStatus reviewStatus,
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
