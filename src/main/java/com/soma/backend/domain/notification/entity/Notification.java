package com.soma.backend.domain.notification.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * NOTIFICATIONS Aggregate Root — 사용자별 인앱 알림 1건(제목·본문·읽음 여부).
 *
 * <p>발송 토큰(device_tokens)·수신 설정(notification_settings)과 분리된, 인앱 알림함 목록·읽음
 * 관리 전용 테이블이다. 수신자(USERS)는 객체가 아니라 user_id(UUID)로만 참조한다(Aggregate 간 ID 참조).
 * 생성 후 읽음만 전이하는 모델이라 updated_at은 두지 않고 created_at만 감사(auditing)한다.
 */
@Entity
@Table(name = "notifications")
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 30)
  private NotificationType type;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "body")
  private String body;

  @Column(name = "is_read", nullable = false)
  private boolean isRead;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 알림 1건 생성 팩터리. 읽음 상태는 항상 미읽음(false)으로 시작한다.
   * 실제 생성 배선(producer: 이벤트→row insert)은 별도 티켓 범위다.
   */
  public static Notification create(UUID userId, NotificationType type, String title, @Nullable String body) {
    Notification notification = new Notification();
    notification.userId = userId;
    notification.type = type;
    notification.title = title;
    notification.body = body;
    notification.isRead = false;
    return notification;
  }

  /** 읽음 처리. 이미 읽음이면 상태 변화 없이 멱등하다. */
  public void markRead() {
    this.isRead = true;
  }

  /** 수신자 본인 여부 — 본인 알림만 조작하도록 서비스가 소유권 검사에 사용한다. */
  public boolean isOwnedBy(UUID candidateUserId) {
    return this.userId.equals(candidateUserId);
  }
}
