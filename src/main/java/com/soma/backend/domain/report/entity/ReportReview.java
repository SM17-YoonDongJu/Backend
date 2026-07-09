package com.soma.backend.domain.report.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * REPORT_REVIEWS Aggregate Root — 사정사 검수 작업 공간(고객 노출). (report_id, adjuster_id) 당 1행(UK).
 * AI 초안(REPORTS)과 격리되어 사정사 수정본만 담는다(design.md A8, glossary §13).
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

  @Column(name = "review")
  private String review;

  @Column(name = "estimate_min_amount")
  private Long estimateMinAmount;

  @Column(name = "estimate_max_amount")
  private Long estimateMaxAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "applicable_guarantees", columnDefinition = "text[]")
  private List<String> applicableGuarantees;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "omitted_special_contract", columnDefinition = "text[]")
  private List<String> omittedSpecialContract;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "basis_terms_precedents", columnDefinition = "text[]")
  private List<String> basisTermsPrecedents;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReviewStatus status;

  /** 사정사별 쟁점 검수 — 이 Aggregate가 소유·생명주기 관리(외부 Repository로 직접 저장/삭제하지 않음). */
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "report_review_id", nullable = false)
  private List<ReportReviewIssue> issues = new ArrayList<>();

  public ReportReview(UUID reportId, UUID adjusterId) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
    this.status = ReviewStatus.SENT;
  }

  /** 사용자가 이 제안을 채택. 리포트 종결과 함께 호출된다(design.md §6 decide). */
  public void accept() {
    this.status = ReviewStatus.ACCEPTED;
  }

  /** 사용자가 이 제안을 거절. 목록에서 제외된다(리포트 상태는 유지). */
  public void reject() {
    this.status = ReviewStatus.REJECTED;
  }

  /** 검수 내용 갱신(estimate·배열3·review). 서명 개념 없음 — status 전이와 무관하게 upsert된다. */
  public void updateReviewContent(Long estimateMinAmount, Long estimateMaxAmount,
      List<String> applicableGuarantees, List<String> omittedSpecialContract,
      List<String> basisTermsPrecedents, String review) {
    this.estimateMinAmount = estimateMinAmount;
    this.estimateMaxAmount = estimateMaxAmount;
    this.applicableGuarantees = applicableGuarantees;
    this.omittedSpecialContract = omittedSpecialContract;
    this.basisTermsPrecedents = basisTermsPrecedents;
    this.review = review;
  }

  /**
   * 사정사 쟁점 검수를 교체한다(Aggregate 내부에서 처리). 같은 AI 쟁점(report_issue_id)은
   * 삭제·재삽입 대신 값만 갱신하고, 없어진 것은 제거, 새 것은 추가한다(부분 UK 충돌 회피).
   * ADDED(report_issue_id null)는 UK가 없으므로 전량 교체한다.
   */
  public void replaceIssues(List<ReportReviewIssue> desired) {
    this.issues.removeIf(cur -> cur.getReportIssueId() == null);

    Map<UUID, ReportReviewIssue> currentByIssueId = new HashMap<>();
    for (ReportReviewIssue cur : this.issues) {
      currentByIssueId.put(cur.getReportIssueId(), cur);
    }

    Set<UUID> desiredIssueIds = new HashSet<>();
    for (ReportReviewIssue want : desired) {
      if (want.getReportIssueId() == null) {
        this.issues.add(want);
        continue;
      }
      desiredIssueIds.add(want.getReportIssueId());
      ReportReviewIssue cur = currentByIssueId.get(want.getReportIssueId());
      if (cur != null) {
        cur.updateContent(want.getTitle(), want.getDescription(), want.getReviewStatus(),
            want.getAdjusterOpinion(), want.getModifiedReason(), want.getExcludedReason());
      } else {
        this.issues.add(want);
      }
    }

    this.issues.removeIf(
        cur -> cur.getReportIssueId() != null && !desiredIssueIds.contains(cur.getReportIssueId()));
  }
}
