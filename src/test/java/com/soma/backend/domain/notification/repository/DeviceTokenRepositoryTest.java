package com.soma.backend.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Platform;

/**
 * DeviceTokenRepository 통합 테스트(실제 test_db). 파생 쿼리 동작·소유권 스코핑·UNIQUE(user_id, token)
 * 제약 강제를 검증한다. @Transactional로 각 테스트 종료 시 롤백된다. 로컬 docker PostgreSQL(test_db) 필요.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("DeviceTokenRepository 통합 테스트 (test_db)")
class DeviceTokenRepositoryTest {

  @Autowired
  private DeviceTokenRepository deviceTokenRepository;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("findByUserIdAndToken-저장한 토큰은 찾고 다른 user/token은 찾지 못한다")
  void findByUserIdAndToken_scoped() {
    // Given
    UUID userId = UUID.randomUUID();
    String token = "tok-" + UUID.randomUUID();
    deviceTokenRepository.saveAndFlush(DeviceToken.create(userId, token, Platform.ANDROID));

    // When & Then
    assertThat(deviceTokenRepository.findByUserIdAndToken(userId, token)).isPresent();
    assertThat(deviceTokenRepository.findByUserIdAndToken(UUID.randomUUID(), token)).isEmpty();
    assertThat(deviceTokenRepository.findByUserIdAndToken(userId, "other-token")).isEmpty();
  }

  @Test
  @DisplayName("deleteByUserIdAndToken-본인 토큰만 삭제하고 타인 토큰은 못 지운다(소유권 스코핑)")
  void deleteByUserIdAndToken_scopedToOwner() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    String token = "tok-" + UUID.randomUUID();
    deviceTokenRepository.saveAndFlush(DeviceToken.create(userId, token, Platform.ANDROID));

    // When & Then
    assertThat(deviceTokenRepository.deleteByUserIdAndToken(otherId, token)).isZero();
    assertThat(deviceTokenRepository.deleteByUserIdAndToken(userId, token)).isEqualTo(1);
  }

  @Test
  @DisplayName("deleteByUserIdAndToken-없는 토큰 삭제는 0행 no-op이다(멱등, BC3)")
  void deleteByUserIdAndToken_absentIsNoOp() {
    // When & Then
    assertThat(deviceTokenRepository.deleteByUserIdAndToken(UUID.randomUUID(), "absent-token"))
        .isZero();
  }

  @Test
  @DisplayName("deleteByUserIdAndTokenIn-여러 죽은 토큰을 한 번에 삭제하고 타인 토큰은 남긴다")
  void deleteByUserIdAndTokenIn_bulkScoped() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID otherId = UUID.randomUUID();
    String tokenA = "tok-a-" + UUID.randomUUID();
    String tokenB = "tok-b-" + UUID.randomUUID();
    String otherToken = "tok-o-" + UUID.randomUUID();
    deviceTokenRepository.saveAndFlush(DeviceToken.create(userId, tokenA, Platform.ANDROID));
    deviceTokenRepository.saveAndFlush(DeviceToken.create(userId, tokenB, Platform.IOS));
    deviceTokenRepository.saveAndFlush(DeviceToken.create(otherId, otherToken, Platform.WEB));

    // When
    int deleted = deviceTokenRepository.deleteByUserIdAndTokenIn(userId, List.of(tokenA, tokenB));
    entityManager.flush();
    entityManager.clear();

    // Then
    assertThat(deleted).isEqualTo(2);
    assertThat(deviceTokenRepository.findByUserId(userId)).isEmpty();
    assertThat(deviceTokenRepository.findByUserId(otherId)).hasSize(1);
  }

  @Test
  @DisplayName("같은 (user_id, token) 중복 저장은 UNIQUE 제약 위반으로 막힌다")
  void saveDuplicateUserToken_violatesUnique() {
    // Given
    UUID userId = UUID.randomUUID();
    String token = "tok-" + UUID.randomUUID();
    deviceTokenRepository.saveAndFlush(DeviceToken.create(userId, token, Platform.ANDROID));

    // When & Then
    DeviceToken duplicate = DeviceToken.create(userId, token, Platform.IOS);
    assertThatThrownBy(() -> deviceTokenRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
