package com.soma.backend.domain.report.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.report.entity.UserClaim;

/** UserClaim Aggregate Spring Data JPA 리포지토리. */
public interface UserClaimRepository extends JpaRepository<UserClaim, UUID> {
}
