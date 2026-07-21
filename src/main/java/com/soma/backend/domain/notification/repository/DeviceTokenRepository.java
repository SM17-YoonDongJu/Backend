package com.soma.backend.domain.notification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import com.soma.backend.domain.notification.entity.DeviceToken;

/**
 * DEVICE_TOKENS Aggregate Spring Data JPA 리포지토리 — (user_id, token) 조회·발송 대상 로드·정리.
 * 모든 조회/삭제는 파생 쿼리로 작성한다(native 금지). 삭제는 user_id 스코핑으로 소유권을 강제한다.
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

  /** 멱등 upsert용 단건 조회 — (user_id, token) UNIQUE 기준. 타인 토큰은 애초에 매칭되지 않는다. */
  Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token);

  /** 발송 대상 로드 — 사용자의 모든 디바이스 토큰. */
  List<DeviceToken> findByUserId(UUID userId);

  /** 해제(멱등) — 본인 소유 (user_id, token) 1건 삭제. 반환은 삭제된 행 수(없으면 0). */
  @Modifying
  int deleteByUserIdAndToken(UUID userId, String token);

  /** 죽은 토큰 일괄 정리 — 사용자의 여러 토큰을 한 번에 삭제. 반환은 삭제된 행 수. */
  @Modifying
  int deleteByUserIdAndTokenIn(UUID userId, Collection<String> tokens);
}
