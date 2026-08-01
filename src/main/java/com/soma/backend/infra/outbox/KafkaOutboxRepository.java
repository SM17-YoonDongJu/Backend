package com.soma.backend.infra.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * KafkaOutboxEvent Spring Data JPA 리포지토리.
 * {@link #findBatchForRelay(int)}는 PostgreSQL {@code FOR UPDATE SKIP LOCKED}로 PENDING 행을 잠가
 * 여러 애플리케이션 인스턴스가 동시에 폴링해도 같은 이벤트를 중복 발행하지 않도록 한다(다중 인스턴스 안전).
 */
public interface KafkaOutboxRepository extends JpaRepository<KafkaOutboxEvent, UUID> {

  @Query(value = "SELECT * FROM kafka_outbox_events WHERE status = 'PENDING' "
      + "ORDER BY created_at "
      + "FOR UPDATE SKIP LOCKED "
      + "LIMIT :limit", nativeQuery = true)
  List<KafkaOutboxEvent> findBatchForRelay(@Param("limit") int limit);
}
