package com.soma.backend.domain.adjuster.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.entity.ApplicationStatus;

/** ADJUSTER_APPLICATIONS Aggregate Spring Data JPA 리포지토리. */
public interface AdjusterApplicationRepository extends JpaRepository<AdjusterApplication, UUID> {

  /** 본인의 최신 신청 1건(GET /users/adjuster-applications/me 조회용). */
  Optional<AdjusterApplication> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

  /** 진행 중(PENDING) 신청 존재 여부 — 중복 신청 방지. */
  boolean existsByUserIdAndStatus(UUID userId, ApplicationStatus status);
}
