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

  public static SocialAccount create(UUID userId, String provider, String providerUserId) {
    SocialAccount account = new SocialAccount();
    account.userId = userId;
    account.provider = provider;
    account.providerUserId = providerUserId;
    account.linkedAt = LocalDateTime.now();
    return account;
  }
}
