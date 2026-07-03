package com.soma.backend.domain.report.entity;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  public ReportReview(UUID reportId, UUID adjusterId) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
    this.status = ReviewStatus.SENT;
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
}
