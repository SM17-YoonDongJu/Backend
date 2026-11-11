package com.soma.backend.domain.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.auth.entity.SocialAccount;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, UUID> {

  Optional<SocialAccount> findByProviderAndProviderUserIdHmac(String provider, byte[] providerUserIdHmac);

  boolean existsByProviderAndProviderUserIdHmac(String provider, byte[] providerUserIdHmac);

  List<SocialAccount> findByUserId(UUID userId);

  void deleteByUserId(UUID userId);
}
