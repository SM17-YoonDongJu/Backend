package com.soma.backend.domain.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.auth.entity.SocialAccount;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

  Optional<SocialAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

  boolean existsByProviderAndProviderUserId(String provider, String providerUserId);
}
