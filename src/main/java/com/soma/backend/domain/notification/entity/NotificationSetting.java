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

  /**
   * type→토글 매핑. 해당 {@link NotificationType}의 푸시 발송이 허용되는지(토글 ON) 반환한다.
   * 토글 데이터가 사는 엔티티에서 매핑을 소유해 응집도를 높인다 — type 추가 시 이 switch만 확장한다.
   * 전용 토글이 없는 type은 fail-open(true)으로 두어 미매핑 알림이 조용히 유실되지 않게 한다.
   */
  public boolean allows(NotificationType type) {
    return switch (type) {
      case RECEIVED_PROPOSAL -> receivedProposal;
      case REVIEW_COMPLETE -> reviewComplete;
      case CONSULT_ACCEPTED -> consultAccepted;
      case ANALYSIS_COMPLETE -> analysisComplete;
      case IDENTITY_VERIFIED -> identityVerified;
      case SETTLEMENT_NOTICE -> settlementNotice;
      case NEW_REVIEW_REQUEST -> newReviewRequest;
      case REVIEW_DEADLINE_SOON -> reviewDeadlineSoon;
      case CHAT_MESSAGE, CONSULT_REQUESTED -> consultMessage;
      // 마케팅이 아니라 사용자 액션(재업로드 등)이 필요할 수 있는 시스템 실패 통지다. 토글로 끌 수 있게 하면
      // 이 알림이 없애려는 무음 실패를 다시 만들므로 항상 발송한다(analysis_complete 토글과 분리).
      case ANALYSIS_FAILED -> true;
      // AI 입력 가드레일 차단도 같은 이유로 항상 발송한다(§ANALYSIS_FAILED 주석 참고).
      case REPORT_BLOCKED -> true;
      // OCR 품질 미달은 재업로드라는 사용자 액션이 있어야 진행되는 통지라 더더욱 끌 수 없다.
      case REPORT_NEEDS_REUPLOAD -> true;
      case PROPOSAL_CLOSED -> true;
      default -> true;
    };
  }

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
