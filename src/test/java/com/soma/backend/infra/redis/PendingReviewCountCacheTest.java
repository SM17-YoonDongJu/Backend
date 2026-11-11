package com.soma.backend.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("PendingReviewCountCache 단위 테스트")
class PendingReviewCountCacheTest {

  private static final String FIELD = "-:-:-";

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
  private final PendingReviewCountCache cache = new PendingReviewCountCache(redisTemplate);

  private UUID adjusterId;
  private String key;

  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
    key = "pending-review:count:" + adjusterId;
    given(redisTemplate.opsForHash()).willReturn(hashOps);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("미스면 loader 값을 HSET·조건부 EXPIRE(30초) 스크립트 한 번으로 저장한다")
  void missLoadsStoresAndSetsTtl() {
    given(hashOps.get(key, FIELD)).willReturn(null);

    long result = cache.getOrLoad(adjusterId, FIELD, () -> 7L);

    assertThat(result).isEqualTo(7L);
    verifyScriptWrite(FIELD, "7");
  }

  @Test
  @DisplayName("히트면 loader를 호출하지 않고 캐시 값을 반환한다")
  void hitSkipsLoader() {
    given(hashOps.get(key, FIELD)).willReturn("12");
    AtomicInteger calls = new AtomicInteger();

    long result = cache.getOrLoad(adjusterId, FIELD, () -> {
      calls.incrementAndGet();
      return 99L;
    });

    assertThat(result).isEqualTo(12L);
    assertThat(calls).hasValue(0);
    verifyNoScriptWrite();
  }

  @Test
  @DisplayName("저장은 원자 스크립트로만 나가고 HSET·TTL 조회·EXPIRE를 따로 보내지 않는다(부분 실패로 TTL 없는 키 방지)")
  void writeIsSingleAtomicScript() {
    given(hashOps.get(key, "v:AWAITING_INSPECTION:-:-")).willReturn(null);

    cache.getOrLoad(adjusterId, "v:AWAITING_INSPECTION:-:-", () -> 3L);

    verifyScriptWrite("v:AWAITING_INSPECTION:-:-", "3");
    verify(hashOps, never()).put(any(), any(), any());
    verify(redisTemplate, never()).getExpire(anyString());
    verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
  }

  @Test
  @DisplayName("Redis 조회 실패 시 loader 결과(DB count)로 대체하고 예외를 던지지 않는다")
  void redisReadFailureFallsBackToLoader() {
    given(hashOps.get(key, FIELD)).willThrow(new RedisConnectionFailureException("redis down"));

    long result = cache.getOrLoad(adjusterId, FIELD, () -> 5L);

    assertThat(result).isEqualTo(5L);
    // 조회가 Redis 장애로 실패했으면 저장을 건너뛴다(응답 없는 Redis를 두 번 기다리지 않는다).
    verifyNoScriptWrite();
  }

  @Test
  @DisplayName("저장 스크립트가 Redis 예외로 실패해도 loader 결과를 반환한다")
  void redisWriteFailureIsSwallowed() {
    given(hashOps.get(key, FIELD)).willReturn(null);
    given(redisTemplate.execute(any(RedisScript.class), any(List.class), any(), any(), any()))
        .willThrow(new RedisConnectionFailureException("redis down"));

    assertThat(cache.getOrLoad(adjusterId, FIELD, () -> 6L)).isEqualTo(6L);
  }

  @Test
  @DisplayName("loader(DB) 예외는 Redis 장애로 오인해 삼키지 않고 그대로 전파하며 아무것도 저장하지 않는다")
  void loaderExceptionPropagates() {
    given(hashOps.get(key, FIELD)).willReturn(null);
    IllegalStateException dbFailure = new IllegalStateException("db down");

    assertThatThrownBy(() -> cache.getOrLoad(adjusterId, FIELD, () -> {
      throw dbFailure;
    })).isSameAs(dbFailure);
    verify(hashOps, never()).put(any(), any(), any());
    verifyNoScriptWrite();
  }

  @Test
  @DisplayName("Redis 데이터 접근 예외 계열(QueryTimeoutException 등)이 loader 안에서 나도 전파한다 — DB 예외 보호")
  void loaderDataAccessExceptionPropagates() {
    given(hashOps.get(key, FIELD)).willReturn(null);
    QueryTimeoutException dbTimeout = new QueryTimeoutException("statement timeout");

    assertThatThrownBy(() -> cache.getOrLoad(adjusterId, FIELD, () -> {
      throw dbTimeout;
    })).isSameAs(dbTimeout);
  }

  @Test
  @DisplayName("손상된 캐시 값은 미스로 보고 다시 적재한다")
  void corruptedValueTreatedAsMiss() {
    given(hashOps.get(key, FIELD)).willReturn("not-a-number");

    assertThat(cache.getOrLoad(adjusterId, FIELD, () -> 4L)).isEqualTo(4L);
    verifyScriptWrite(FIELD, "4");
  }

  @Test
  @DisplayName("트랜잭션 밖 evict는 즉시 키를 지운다 — 이후 조회는 미스")
  void evictOutsideTransactionDeletesImmediately() {
    cache.evictAfterCommit(adjusterId);

    verify(redisTemplate).delete(key);
  }

  @Test
  @DisplayName("트랜잭션 안 evict는 커밋 후에만 지운다(롤백·커밋 전에는 유지)")
  void evictInsideTransactionDeletesAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();

    cache.evictAfterCommit(adjusterId);
    verify(redisTemplate, never()).delete(anyString());

    List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
    assertThat(syncs).hasSize(1);
    syncs.get(0).afterCommit();
    verify(redisTemplate).delete(key);
  }

  @Test
  @DisplayName("무효화 중 Redis 실패는 삼킨다(TTL이 상한)")
  void evictFailureIsSwallowed() {
    given(redisTemplate.delete(key)).willThrow(new RedisConnectionFailureException("redis down"));

    cache.evictAfterCommit(adjusterId);

    verify(redisTemplate).delete(key);
  }

  @SuppressWarnings("unchecked")
  private void verifyScriptWrite(String field, String value) {
    verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)), eq(field), eq(value), eq("30"));
  }

  @SuppressWarnings("unchecked")
  private void verifyNoScriptWrite() {
    verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(), any(), any());
  }
}
