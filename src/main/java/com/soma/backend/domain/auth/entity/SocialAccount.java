package com.soma.backend.domain.auth.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.global.security.crypto.converter.SocialAccountProviderUserIdConverter;

/**
 * SOCIAL_ACCOUNTS Aggregate Root. 소셜 로그인 제공자 계정과 회원을 연결한다.
 *
 * <p>User Aggregate와는 user_id(UUID)로만 참조한다(객체 참조 금지). (provider, providerUserId)로
 * 로그인 시 회원을 식별한다 — 다만 {@code providerUserId} 자체는 암호화 컬럼이라 실제 조회·유일성은
 * {@link #providerUserIdHmac}(HMAC 블라인드 인덱스, 이슈 #232)로 한다.
 *
 * <p>{@code refreshToken}은 Apple 탈퇴 revoke에 쓰는 제공자 refresh_token이며 <b>암호문</b>(AES-GCM,
 * {@code AesGcmCipher})으로만 저장한다. 평문을 여기에 넣지 않는다. Apple 외 provider는 {@code null}.
 */
@Entity
@Table(name = "social_accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id_hmac"}))
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

  /** AES-256-GCM 암호화(봉투). AAD=social_accounts:provider_user_id. 조회는 {@link #providerUserIdHmac}로 한다. */
  @Convert(converter = SocialAccountProviderUserIdConverter.class)
  @Column(name = "provider_user_id", nullable = false)
  private String providerUserId;

  /** providerUserId의 HMAC-SHA256 블라인드 인덱스(조회 전용, 이슈 #232). */
  @Column(name = "provider_user_id_hmac", nullable = false)
  private byte[] providerUserIdHmac;

  @Column(name = "linked_at", nullable = false)
  private LocalDateTime linkedAt;

  /**
   * 제공자 refresh_token의 암호문(AES-GCM, base64). 탈퇴 revoke에만 쓴다. Apple 외에는 {@code null}.
   */
  @Column(name = "refresh_token", length = 1024)
  private String refreshToken;

  public static SocialAccount create(
      UUID userId, String provider, String providerUserId, byte[] providerUserIdHmac) {
    SocialAccount account = new SocialAccount();
    account.userId = userId;
    account.provider = provider;
    account.providerUserId = providerUserId;
    account.providerUserIdHmac = providerUserIdHmac;
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
