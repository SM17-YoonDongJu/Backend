package com.soma.backend.domain.auth.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SOCIAL_ACCOUNTS Aggregate Root. 소셜 로그인 제공자 계정과 회원을 연결한다.
 *
 * <p>User Aggregate와는 user_id(UUID)로만 참조한다(객체 참조 금지). (provider, providerUserId)로
 * 로그인 시 회원을 식별한다.
 *
 * <p>{@code refreshToken}은 Apple 탈퇴 revoke에 쓰는 제공자 refresh_token이며 <b>암호문</b>(AES-GCM,
 * {@code AesGcmCipher})으로만 저장한다. 평문을 여기에 넣지 않는다. Apple 외 provider는 {@code null}.
 */
@Entity
@Table(name = "social_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "provider", nullable = false, length = 20)
  private String provider;

  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  @Column(name = "linked_at", nullable = false)
  private LocalDateTime linkedAt;

  /**
   * 제공자 refresh_token의 암호문(AES-GCM, base64). 탈퇴 revoke에만 쓴다. Apple 외에는 {@code null}.
   */
  @Column(name = "refresh_token", length = 1024)
  private String refreshToken;

  public static SocialAccount create(UUID userId, String provider, String providerUserId) {
    SocialAccount account = new SocialAccount();
    account.userId = userId;
    account.provider = provider;
    account.providerUserId = providerUserId;
    account.linkedAt = LocalDateTime.now();
    return account;
  }

  /**
   * 제공자 refresh_token(암호문)을 연결한다. 반드시 {@code AesGcmCipher.encrypt}로 암호화한 값을 넘긴다
   * (setter를 열지 않고 리치 모델로 상태를 바꾼다).
   */
  public void linkRefreshToken(String encryptedRefreshToken) {
    this.refreshToken = encryptedRefreshToken;
  }
}
