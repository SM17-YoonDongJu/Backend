package com.soma.backend.report.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * REPORTS Aggregate Root. AI 초안 리포트와 사정사 검수 확정 상태를 함께 관리한다.
 * 상태 전이는 {@link #applyReviewTransition(ReportStatus)}로만 허용한다(design.md §4).
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

  private static final Map<ReportStatus, Set<ReportStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ReportStatus.class);

  static {
    ALLOWED_TRANSITIONS.put(ReportStatus.AWAITING_INSPECTION, EnumSet.of(ReportStatus.AWAITING_ADOPTION));
    ALLOWED_TRANSITIONS.put(
        ReportStatus.AWAITING_ADOPTION, EnumSet.of(ReportStatus.AWAITING_ADOPTION, ReportStatus.MATCHED));
    ALLOWED_TRANSITIONS.put(ReportStatus.COUNSELING, EnumSet.of(ReportStatus.MATCHED));
    ALLOWED_TRANSITIONS.put(ReportStatus.MATCHED, EnumSet.noneOf(ReportStatus.class));
  }

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id")
  private UUID adjusterId;

  @Column(name = "case_no", nullable = false, length = 100)
  private String caseNo;

  @Column(name = "title")
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "accident_type", nullable = false, length = 30)
  private AccidentType accidentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReportStatus status;

  @Column(name = "region", length = 100)
  private String region;

  @Column(name = "claimed_min_amount")
  private Long claimedMinAmount;

  @Column(name = "claimed_max_amount")
  private Long claimedMaxAmount;

  @Column(name = "offered_amount")
  private Long offeredAmount;

  public AmountRange amountRange() {
    return new AmountRange(claimedMinAmount, claimedMaxAmount, offeredAmount);
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
