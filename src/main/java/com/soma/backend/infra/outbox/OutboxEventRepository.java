package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * 처리 대상(PENDING·백오프 경과) 이벤트를 오래된 순으로 조회하되 {@code FOR UPDATE SKIP LOCKED}로
   * 잠가, 다중 인스턴스가 같은 행을 중복 처리하지 않게 한다. 반드시 트랜잭션 안에서 호출한다.
   */
  @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now "
      + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<OutboxEvent> lockPendingBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
