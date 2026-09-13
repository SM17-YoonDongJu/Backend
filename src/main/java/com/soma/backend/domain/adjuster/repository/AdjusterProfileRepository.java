package com.soma.backend.domain.adjuster.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;

/** 손해사정사 프로필 조회 리포지토리(본인 프로필·공개 검색). 동적 목록/검색은 {@link AdjusterProfileRepositoryCustom}. */
public interface AdjusterProfileRepository
    extends JpaRepository<AdjusterProfile, UUID>, AdjusterProfileRepositoryCustom {

  Optional<AdjusterProfile> findByUserId(UUID userId);

  /**
   * 비정규화 집계 갱신 전용 잠금 조회 — 프로필 행을 {@code PESSIMISTIC_WRITE}로 잡아 같은 사정사에 대한
   * 동시 재집계를 직렬화한다.
   *
   * <p>재집계는 read-modify-write라 잠금이 없으면 lost update가 난다. 서로 다른 사용자가 같은 사정사를
   * 동시에 평가하거나(중복 방지 UK가 (user, adjuster)라 서로를 막지 않는다), 같은 사정사의 서로 다른
   * 리포트가 동시에 채택되면 두 트랜잭션이 각자 옛 모수로 집계해 나중 커밋이 먼저 커밋을 덮어쓴다.
   * 잠금을 먼저 잡고 집계 쿼리를 실행하므로 두 번째 트랜잭션은 첫 번째의 커밋 결과를 보고 계산한다.
   *
   * <p>이 잠금을 지우면 {@code AdjusterReviewCommandServiceConcurrencyIntegrationTest}(평가 동시 등록)와
   * {@code ChatConsultationAcceptConcurrencyIntegrationTest}(리포트 2건 동시 수락)가 집계값 1(기대 2)로
   * 깨진다 — 실제 두 트랜잭션을 붙여 확인한 값이다.
   *
   * <p>집계 갱신 경로 전용이다 — 잠금이 필요 없는 조회는 {@link #findByUserId}를 그대로 쓴다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT ap FROM AdjusterProfile ap WHERE ap.userId = :userId")
  Optional<AdjusterProfile> findByUserIdForUpdate(@Param("userId") UUID userId);
}
