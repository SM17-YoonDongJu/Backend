package com.soma.backend.domain.notification.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.notification.entity.Notification;

/** NOTIFICATIONS Aggregate Spring Data JPA 리포지토리 — 사용자별 목록·미읽음 카운트·읽음 처리. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  /** 사용자별 최신순 목록(페이지네이션). 인덱스 (user_id, created_at DESC)를 탄다. */
  Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  /** 사용자별 미읽음 개수. 인덱스 (user_id, is_read)를 탄다. */
  long countByUserIdAndIsReadFalse(UUID userId);

  /** 본인 소유 알림 단건 조회(id + user_id). 소유자가 아니면 empty를 반환해 존재 자체를 숨긴다. */
  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

  /**
   * 사용자의 미읽음 알림을 일괄 읽음 처리(벌크 UPDATE). 이미 읽은 행은 건드리지 않는다.
   * 벌크 연산이라 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에서 대상 엔티티를 이후 재사용하지 않는다.
   *
   * @return 읽음으로 바뀐 행 수
   */
  @Modifying
  @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
  int markAllReadByUserId(@Param("userId") UUID userId);
}
