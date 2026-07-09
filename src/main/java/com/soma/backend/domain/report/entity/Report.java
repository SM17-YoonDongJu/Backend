package com.soma.backend.domain.report.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * REPORTS Aggregate Root. AI 초안 리포트와 사정사 검수 확정 상태를 함께 관리한다.
 * 상태 전이는 {@link #applyReviewTransition(ReportStatus)}로만 허용한다(design.md §4).
 * region 컬럼은 없다 — user_id로 users.region을 조인해서 조회한다(§1).
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

  private static final Map<ReportStatus, Set<ReportStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ReportStatus.class);

  static {
    ALLOWED_TRANSITIONS.put(ReportStatus.AWAITING_INSPECTION,
        EnumSet.of(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION));
    ALLOWED_TRANSITIONS.put(ReportStatus.AWAITING_ADOPTION,
        EnumSet.of(ReportStatus.AWAITING_ADOPTION, ReportStatus.COUNSELING));
    ALLOWED_TRANSITIONS.put(ReportStatus.COUNSELING,
        EnumSet.of(ReportStatus.COUNSELING, ReportStatus.CLOSED));
    ALLOWED_TRANSITIONS.put(ReportStatus.CLOSED, EnumSet.of(ReportStatus.CLOSED));
  }

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id")
  private UUID adjusterId;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "claim_id")
  private UUID claimId;

  @Column(name = "case_no", nullable = false, length = 100)
  private String caseNo;

  @Column(name = "title")
  private String title;

  @Convert(converter = AccidentTypeConverter.class)
  @Column(name = "accident_type", nullable = false, length = 30)
  private AccidentType accidentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReportStatus status;

  @Column(name = "claimed_min_amount")
  private Long claimedMinAmount;

  @Column(name = "claimed_max_amount")
  private Long claimedMaxAmount;

  @Column(name = "offered_amount")
  private Long offeredAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "applicable_guarantees", columnDefinition = "text[]")
  private List<String> applicableGuarantees;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "omitted_special_contract", columnDefinition = "text[]")
  private List<String> omittedSpecialContract;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "basis_terms_precedents", columnDefinition = "text[]")
  private List<String> basisTermsPrecedents;

  @Column(name = "treatment")
  private String treatment;

  @Column(name = "question")
  private String question;

  @Column(name = "confidence_level", length = 10)
  private String confidenceLevel;

  @Column(name = "is_masked")
  private Boolean isMasked;

  /**
   * 리포트 생성 진입점(design.md §3). OCR·AI 분석 전 상태이므로 status=AWAITING_INSPECTION으로 시작한다.
   */
  public static Report createPending(UUID userId, UUID productId, UUID claimId, AccidentType accidentType,
      String question, String caseNo) {
    Report report = new Report();
    report.userId = userId;
    report.productId = productId;
    report.claimId = claimId;
    report.accidentType = accidentType;
    report.question = question;
    report.caseNo = caseNo;
    report.status = ReportStatus.AWAITING_INSPECTION;
    return report;
  }

  public AmountRange amountRange() {
    return new AmountRange(claimedMinAmount, claimedMaxAmount, offeredAmount);
  }

  /**
   * 사용자가 제안(REPORT_REVIEWS)을 채택해 담당 사정사를 확정한다(design.md §6 decide).
   * COUNSELING 상태에서만 허용 — 이미 CLOSED면 REPORT_ALREADY_CLOSED, 그 외 상태면
   * INVALID_STATUS_TRANSITION(예: 아직 상담 전).
   */
  public void accept(UUID adjusterId) {
    if (this.status == ReportStatus.CLOSED) {
      throw new BusinessException(ErrorCode.REPORT_ALREADY_CLOSED);
    }
    if (this.status != ReportStatus.COUNSELING) {
      throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
    }
    this.adjusterId = adjusterId;
    applyReviewTransition(ReportStatus.CLOSED);
  }

  /** 리포트 소유자(요청 사용자) 여부 — 상세/제안/decide 인가 가드에 사용(design.md §8). */
  public boolean isOwnedBy(UUID userId) {
    return this.userId != null && this.userId.equals(userId);
  }

  /**
   * 검수 반영에 의한 상태 전이. 허용표(design.md §4)를 벗어나면 400 INVALID_STATUS_TRANSITION.
   */
  public void applyReviewTransition(ReportStatus target) {
    Set<ReportStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(status, Set.of());
    if (!allowed.contains(target)) {
      throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
    }
    this.status = target;
  }
}
