package com.soma.backend.domain.adjuster.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;

/** 손해사정사 프로필 조회 리포지토리(본인 프로필·공개 검색). */
public interface AdjusterProfileRepository extends JpaRepository<AdjusterProfile, UUID> {

  Optional<AdjusterProfile> findByUserId(UUID userId);
}
