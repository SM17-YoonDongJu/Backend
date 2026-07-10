package com.soma.backend.infra.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.extern.slf4j.Slf4j;

/**
 * 탈퇴한 소셜 신원을 기록하는 원장(ledger). 재로그인이 "탈퇴 후 재가입"인지 프론트가 인지할 수 있도록,
 * 탈퇴 시 {@code (provider, providerUserId)}의 단방향 해시를 기록한다. 이름·연락처 같은 개인정보는
 * 저장하지 않는다(SHA-256 해시만 보관).
 *
 * <p>키는 {@code withdrawal:{sha256(provider:providerUserId)}}, TTL은 {@code app.withdrawal.rejoin-window}
 * (기본 30일)다. 기간이 지나면 자동 소멸하며, 재가입이 완료되면 {@link #clear}로 즉시 제거한다. 이 원장은
 * 인지(플래그)용이며 재가입 자체를 막는 쿨다운은 강제하지 않는다.
 */
@Slf4j
@Repository
public class WithdrawalLedgerRepository {

  private static final String PREFIX = "withdrawal:";
  private static final String MARKER = "1";

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration ttl;

  public WithdrawalLedgerRepository(
      RedisTemplate<String, String> redisTemplate,
      @Value("${app.withdrawal.rejoin-window}") Duration rejoinWindow) {
    this.redisTemplate = redisTemplate;
    this.ttl = rejoinWindow;
  }

  /**
   * 탈퇴한 소셜 신원을 원장에 기록한다.
   */
  public void record(String provider, String providerUserId) {
    redisTemplate.opsForValue().set(key(provider, providerUserId), MARKER, ttl);
  }

  /**
   * 해당 소셜 신원이 탈퇴 이력이 있는지 조회한다. 재가입 인지(플래그)용 best-effort 조회이므로 Redis
   * 장애 시 fail-open으로 {@code false}를 반환한다 — 원장 조회 실패가 로그인/가입 자체를 막지 않는다.
   */
  public boolean wasWithdrawn(String provider, String providerUserId) {
    try {
      return Boolean.TRUE.equals(redisTemplate.hasKey(key(provider, providerUserId)));
    } catch (DataAccessException ex) {
      log.warn("탈퇴 원장 조회 실패, fail-open 처리: {}", ex.getMessage());
      return false;
    }
  }

  /**
   * 재가입이 완료된 소셜 신원의 탈퇴 이력을 원장에서 제거한다.
   */
  public void clear(String provider, String providerUserId) {
    redisTemplate.delete(key(provider, providerUserId));
  }

  private String key(String provider, String providerUserId) {
    return PREFIX + hash(provider + ":" + providerUserId);
  }

  private String hash(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashed);
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256은 모든 JVM에 존재하므로 실질적으로 도달하지 않는다.
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", ex);
    }
  }
}
