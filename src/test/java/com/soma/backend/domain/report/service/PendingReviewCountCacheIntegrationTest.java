package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.infra.redis.PendingReviewCountCache;

/**
 * 실제 Redis(로컬 docker compose의 redis, application-test.yml localhost:6379)와 test_db로 검수대기 count
 * 캐시를 검증한다. 히트 시 count 쿼리가 나가지 않는 것은 Hibernate 통계(실행된 HQL 수)로 확인한다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@Transactional
class PendingReviewCountCacheIntegrationTest {

  @Autowired
  private PendingReviewQueryService pendingReviewQueryService;
  @Autowired
  private PendingReviewCountCache pendingReviewCountCache;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private RedisTemplate<String, String> redisTemplate;
  @Autowired
  private EntityManagerFactory entityManagerFactory;
  @PersistenceContext
  private EntityManager entityManager;

  private UUID adjusterId;
  private String region;
  private String key;

  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
    region = "C-" + UUID.randomUUID();
    key = "pending-review:count:" + adjusterId;
    User customer = User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, List.of(region));
    entityManager.persist(customer);
    for (int idx = 0; idx < 3; idx++) {
      Report report = Report.createPending(
          customer.getId(), null, null, AccidentType.OTHER, "질문", "CC-" + idx + "-" + region);
      reportRepository.save(report);
    }
    entityManager.flush();
    entityManager.clear();
  }

  @AfterEach
  void tearDown() {
    redisTemplate.delete(key);
  }

  @Test
  @DisplayName("두 번째 페이지 요청은 캐시 히트라 count 쿼리가 나가지 않고 total은 그대로다")
  void cacheHitSkipsCountQuery() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

    stats.clear();
    PendingReviewListResponse first = list(0);
    long missQueries = stats.getQueryExecutionCount();

    stats.clear();
    PendingReviewListResponse second = list(1);
    long hitQueries = stats.getQueryExecutionCount();

    assertThat(first.pagination().totalElements()).isEqualTo(3L);
    assertThat(second.pagination().totalElements()).isEqualTo(3L);
    assertThat(second.pagination().totalPages()).isEqualTo(3);
    assertThat(hitQueries).isEqualTo(missQueries - 1);
    assertThat(redisTemplate.<String, String>opsForHash().get(key, "-:-:v:" + region)).isEqualTo("3");
  }

  @Test
  @DisplayName("최초 적재에만 TTL(30초 이하)을 걸고, 다른 필드를 적재해도 남은 TTL을 늘리지 않는다")
  void ttlIsNotRefreshed() {
    list(0);
    Long initialTtl = redisTemplate.getExpire(key);
    assertThat(initialTtl).isBetween(1L, 30L);

    redisTemplate.expire(key, Duration.ofSeconds(10));
    pendingReviewQueryService.getPendingReviewList(
        ReportStatus.AWAITING_INSPECTION.name(), null, region, adjusterId, PageRequest.of(0, 1));

    assertThat(redisTemplate.<String, String>opsForHash().size(key)).isEqualTo(2L);
    assertThat(redisTemplate.getExpire(key)).isBetween(1L, 10L);
  }

  @Test
  @DisplayName("저장 스크립트는 TTL 없는 해시(부분 실패 잔여 등)에도 30초 TTL을 붙인다")
  void scriptAttachesTtlToKeyWithoutTtl() {
    redisTemplate.<String, String>opsForHash().put(key, "stale", "99");
    assertThat(redisTemplate.getExpire(key)).isEqualTo(-1L);

    long loaded = pendingReviewCountCache.getOrLoad(adjusterId, "fresh", () -> 4L);

    assertThat(loaded).isEqualTo(4L);
    assertThat(redisTemplate.<String, String>opsForHash().get(key, "fresh")).isEqualTo("4");
    assertThat(redisTemplate.getExpire(key)).isBetween(1L, 30L);
  }

  private PendingReviewListResponse list(int page) {
    return pendingReviewQueryService.getPendingReviewList(null, null, region, adjusterId, PageRequest.of(page, 1));
  }
}
