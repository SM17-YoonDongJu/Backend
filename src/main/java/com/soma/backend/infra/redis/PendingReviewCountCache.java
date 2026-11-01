package com.soma.backend.infra.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 검수대기 목록(GET /reports/pending-review)의 전체 건수 캐시.
 *
 * <p>왜 캐시하는가: 목록 count 쿼리는 페이지가 바뀌어도 값이 같은데 매 요청 목록 쿼리와 같은 비용(보류·내 검수
 * 상관 서브쿼리)으로 실행돼, 부하테스트에서 이 API가 DB 시간 대부분을 차지했다. 사정사별 해시 하나
 * {@code pending-review:count:{adjusterId}}에 필터 키(호출자가 만든 문자열)별 건수를 둔다 — 사정사 단위로
 * 한 번에 지울 수 있고, 인스턴스가 늘어도 파드 간 값이 같다.
 *
 * <p>왜 TTL을 갱신하지 않는가: 해시에 TTL이 없을 때(최초 적재)만 건다. 필드를 쓸 때마다 갱신하면 다른 필터가
 * 계속 적재되는 동안 오래된 필드가 무기한 살아남는다. 갱신하지 않으면 어떤 값도 최초 적재 후 {@link #TTL}을
 * 넘기지 않는다 — 다른 사람 행동(새 리포트 접수·채택 등)으로 바뀌는 count는 이 상한으로 흡수한다.
 *
 * <p>Redis 장애는 목록 API 장애가 되면 안 된다. 조회·저장·삭제 중 {@link DataAccessException}은 경고만 남기고
 * DB count로 대체하거나(조회 — 이때는 저장도 건너뛴다) 삼킨다(저장·삭제 — TTL이 상한을 보장).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingReviewCountCache {

  static final String PREFIX = "pending-review:count:";
  static final Duration TTL = Duration.ofSeconds(30);
  /**
   * HSET과 "TTL 없으면 EXPIRE"를 한 번에 보낸다. 명령을 나눠 보내면 HSET만 성공하고 EXPIRE가 타임아웃될 때 TTL
   * 없는 키가 남아, 무효화 전까지 옛 count가 영구히 남는다(30초 상한이 깨진다).
   */
  private static final RedisScript<Long> PUT_SCRIPT = RedisScript.of(
      "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) "
      + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end "
      + "return 1", Long.class);

  private final RedisTemplate<String, String> redisTemplate;

  /** 캐시된 count를 돌려주고, 없으면 loader(DB count)로 적재한다. */
  public long getOrLoad(UUID adjusterId, String filterKey, LongSupplier loader) {
    String key = key(adjusterId);
    Lookup lookup = read(key, filterKey);
    if (lookup.value() != null) {
      return lookup.value();
    }
    // loader(DB) 예외는 Redis 장애가 아니므로 대체 대상이 아니다 — try 밖에서 호출해 그대로 전파한다.
    long loaded = loader.getAsLong();
    // 조회가 Redis 장애로 실패했으면 저장도 건너뛴다. 응답 없는 Redis를 한 요청에서 두 번 기다리지 않기 위함이다.
    if (!lookup.redisFailed()) {
      write(key, filterKey, loaded);
    }
    return loaded;
  }

  /**
   * 사정사의 count 캐시를 커밋 후에 지운다. 왜 커밋 후인가: 커밋 전에 지우면 그 사이 들어온 조회가 아직 커밋되지
   * 않은(옛) 상태로 count를 다시 적재해, 무효화가 무의미해진다. 트랜잭션 밖이면 즉시 지운다.
   */
  public void evictAfterCommit(UUID adjusterId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      evict(adjusterId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        evict(adjusterId);
      }
    });
  }

  private Lookup read(String key, String filterKey) {
    try {
      Object value = redisTemplate.opsForHash().get(key, filterKey);
      return Lookup.of(value == null ? null : Long.valueOf(value.toString()));
    } catch (DataAccessException ex) {
      log.warn("검수대기 count 캐시 조회 실패, DB count로 대체 (key={}): {}", key, ex.getMessage());
      return Lookup.FAILED;
    } catch (NumberFormatException ex) {
      log.warn("검수대기 count 캐시 값 손상, 미스로 처리 (key={}, field={})", key, filterKey);
      return Lookup.MISS;
    }
  }

  private void write(String key, String filterKey, long count) {
    try {
      // TTL이 없을 때(최초 적재)만 건다. 이미 걸려 있으면 그대로 둬 최초 적재 시각 기준 상한을 지킨다.
      redisTemplate.execute(PUT_SCRIPT, List.of(key), filterKey, Long.toString(count),
          Long.toString(TTL.toSeconds()));
    } catch (DataAccessException ex) {
      log.warn("검수대기 count 캐시 저장 실패 (key={}): {}", key, ex.getMessage());
    }
  }

  private void evict(UUID adjusterId) {
    try {
      redisTemplate.delete(key(adjusterId));
    } catch (DataAccessException ex) {
      log.warn("검수대기 count 캐시 무효화 실패, TTL로 만료 대기 (adjusterId={}): {}", adjusterId, ex.getMessage());
    }
  }

  private static String key(UUID adjusterId) {
    return PREFIX + adjusterId;
  }

  /** 조회 결과. value가 null이면 미스이고, redisFailed면 Redis 장애로 조회하지 못한 것이다. */
  private record Lookup(@Nullable Long value, boolean redisFailed) {

    static final Lookup MISS = new Lookup(null, false);
    static final Lookup FAILED = new Lookup(null, true);

    static Lookup of(@Nullable Long value) {
      return value == null ? MISS : new Lookup(value, false);
    }
  }
}
