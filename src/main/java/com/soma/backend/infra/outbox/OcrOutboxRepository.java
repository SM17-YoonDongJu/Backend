package com.soma.backend.infra.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * OcrOutboxEvent Spring Data JPA 리포지토리.
 * {@link #findBatchForRelay(int)}는 PostgreSQL {@code FOR UPDATE SKIP LOCKED}로 PENDING 행을 잠가
 * 여러 애플리케이션 인스턴스가 동시에 폴링해도 같은 이벤트를 중복 발행하지 않도록 한다(다중 인스턴스 안전).
 */
public interface OcrOutboxRepository extends JpaRepository<OcrOutboxEvent, UUID> {

  // 테이블 이름이 kafka_ 인 이유는 OcrOutboxEvent javadoc 참조(리네임은 권한 문제로 보류).
  @Query(value = "SELECT * FROM kafka_outbox_events WHERE status = 'PENDING' "
      + "ORDER BY created_at "
      + "FOR UPDATE SKIP LOCKED "
      + "LIMIT :limit", nativeQuery = true)
  List<OcrOutboxEvent> findBatchForRelay(@Param("limit") int limit);

  /**
   * 적체 게이지용 status별 행 수(#306). 파생 쿼리로 충분한 단순 카운트라 QueryDSL을 쓰지 않는다
   * (하네스 쿼리 규칙). {@code idx_kafka_outbox_status_created}의 선두 컬럼이 status라 그대로 받는다.
   */
  long countByStatus(OcrOutboxStatus status);
}
