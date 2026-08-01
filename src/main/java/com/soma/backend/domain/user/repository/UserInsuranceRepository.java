package com.soma.backend.domain.user.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.user.entity.UserInsurance;

/**
 * USER_INSURANCES 저장소. 본인 소유 보험 목록을 최신 등록순으로 조회한다(단순 파생 쿼리).
 */
public interface UserInsuranceRepository extends JpaRepository<UserInsurance, UUID> {

  List<UserInsurance> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
