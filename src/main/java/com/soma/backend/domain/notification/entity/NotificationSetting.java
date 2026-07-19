package com.soma.backend.domain.notification.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * NOTIFICATION_SETTINGS — 사용자별 알림 수신 토글(USERS 1:1, user_id PK).
 *
 * <p>각 토글은 특정 {@link NotificationType}의 발송 여부를 제어한다("설정 off면 해당 type 미발송").
 * type↔토글 매핑과 실제 억제는 producer 배선에서 적용한다. V21에서
 * consult_accepted·analysis_complete·identity_verified·review_deadline_soon 4개를 추가했다.
 */
@Entity
@Table(name = "notification_settings")
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationSetting {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "new_review_request", nullable = false)
  private boolean newReviewRequest;

  @Column(name = "consult_message", nullable = false)
  private boolean consultMessage;

  @Column(name = "settlement_notice", nullable = false)
  private boolean settlementNotice;

  @Column(name = "review_deadline_soon", nullable = false)
  private boolean reviewDeadlineSoon;

  @Column(name = "review_complete", nullable = false)
  private boolean reviewComplete;

  @Column(name = "received_proposal", nullable = false)
  private boolean receivedProposal;

  @Column(name = "consult_accepted", nullable = false)
  private boolean consultAccepted;

  @Column(name = "analysis_complete", nullable = false)
  private boolean analysisComplete;

  @Column(name = "identity_verified", nullable = false)
  private boolean identityVerified;

  @Column(name = "marketing", nullable = false)
  private boolean marketing;

  @LastModifiedDate
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** 최초 조회 시 없으면 만드는 기본 설정 — DDL DEFAULT와 동일(정산·마케팅만 false). */
  public static NotificationSetting createDefault(UUID userId) {
    NotificationSetting setting = new NotificationSetting();
    setting.userId = userId;
    setting.newReviewRequest = true;
    setting.consultMessage = true;
    setting.settlementNotice = false;
    setting.reviewDeadlineSoon = true;
    setting.reviewComplete = true;
    setting.receivedProposal = true;
    setting.consultAccepted = true;
    setting.analysisComplete = true;
    setting.identityVerified = true;
    setting.marketing = false;
    return setting;
  }

  /** 부분 수정 — {@code null} 인 토글은 그대로 둔다(변경 대상만 전달). */
  public void applyPatch(
      @Nullable Boolean newReviewRequest,
      @Nullable Boolean consultMessage,
      @Nullable Boolean settlementNotice,
      @Nullable Boolean reviewDeadlineSoon,
      @Nullable Boolean reviewComplete,
      @Nullable Boolean receivedProposal,
      @Nullable Boolean consultAccepted,
      @Nullable Boolean analysisComplete,
      @Nullable Boolean identityVerified,
      @Nullable Boolean marketing) {
    if (newReviewRequest != null) {
      this.newReviewRequest = newReviewRequest;
    }
    if (consultMessage != null) {
      this.consultMessage = consultMessage;
    }
    if (settlementNotice != null) {
      this.settlementNotice = settlementNotice;
    }
    if (reviewDeadlineSoon != null) {
      this.reviewDeadlineSoon = reviewDeadlineSoon;
    }
    if (reviewComplete != null) {
      this.reviewComplete = reviewComplete;
    }
    if (receivedProposal != null) {
      this.receivedProposal = receivedProposal;
    }
    if (consultAccepted != null) {
      this.consultAccepted = consultAccepted;
    }
    if (analysisComplete != null) {
      this.analysisComplete = analysisComplete;
    }
    if (identityVerified != null) {
      this.identityVerified = identityVerified;
    }
    if (marketing != null) {
      this.marketing = marketing;
    }
  }
}
