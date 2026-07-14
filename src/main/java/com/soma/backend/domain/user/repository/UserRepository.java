package com.soma.backend.domain.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

  boolean existsByPhoneNumber(String phoneNumber);

  Optional<User> findByNickname(String nickname);
}
