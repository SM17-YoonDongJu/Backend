package com.soma.backend.domain.notification.entity;

import java.time.LocalDateTime;
import java.util.UUID;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * DEVICE_TOKENS Aggregate Root — 사용자 1인의 디바이스 푸시 토큰(FCM/APNs) 1건.
 *
 * <p>인앱 알림(NOTIFICATIONS)·수신 설정(NOTIFICATION_SETTINGS)과 분리된 독립 Aggregate다. 토큰은
 * 기기 단위로 발급/폐기되며 생성·삭제만 있고 in-place 상태전이가 없다(setter 없음). 소유자(USERS)는
 * 객체가 아니라 user_id(UUID)로만 참조한다(Aggregate 간 ID 참조). created_at만 감사(auditing)한다.
 */
@Entity
@Table(
    name = "device_tokens",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_device_tokens_user_token",
        columnNames = {"user_id", "token"}))
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token", nullable = false, length = 500)
  private String token;

  @Enumerated(EnumType.STRING)
  @Column(name = "platform", nullable = false, length = 10)
  private Platform platform;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /**
   * 디바이스 토큰 1건 생성 팩터리. 불변식(userId·platform 필수, token 공백 불가)을 생성 시점에 강제한다.
   * DTO의 {@code @Valid}가 앞단에서 이미 막으므로 여기 가드는 방어적 최후 방벽이다(도달 시 프로그래밍 오류).
   */
  public static DeviceToken create(UUID userId, String token, Platform platform) {
    if (userId == null) {
      throw new IllegalArgumentException("userId는 필수입니다.");
    }
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token은 비어 있을 수 없습니다.");
    }
    if (platform == null) {
      throw new IllegalArgumentException("platform은 필수입니다.");
    }
    DeviceToken deviceToken = new DeviceToken();
    deviceToken.userId = userId;
    deviceToken.token = token;
    deviceToken.platform = platform;
    return deviceToken;
  }
}
