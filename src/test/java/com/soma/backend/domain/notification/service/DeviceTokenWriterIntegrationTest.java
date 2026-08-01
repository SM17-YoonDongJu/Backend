package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;

/**
 * DeviceTokenWriter의 REQUIRES_NEW 격리 특성을 실제 test_db 트랜잭션으로 입증한다. 이 테스트는
 * @Transactional을 붙이지 않는다 — writer.insert의 독립 커밋과 바깥 트랜잭션 격리를 실제로 관찰해야 하기
 * 때문이다. 생성한 행은 @AfterEach에서 정리한다. 로컬 docker PostgreSQL(test_db) 필요.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("DeviceTokenWriter 통합 테스트 (REQUIRES_NEW 격리)")
class DeviceTokenWriterIntegrationTest {

  @Autowired
  private DeviceTokenWriter deviceTokenWriter;

  @Autowired
  private DeviceTokenRepository deviceTokenRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private final UUID userId = UUID.randomUUID();
  private final String token = "fcm-writer-it-" + UUID.randomUUID();

  @AfterEach
  void cleanUp() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> deviceTokenRepository.deleteByUserIdAndToken(userId, token));
  }

  private RegisterDeviceTokenRequest request() {
    return new RegisterDeviceTokenRequest(token, Platform.ANDROID);
  }

  @Test
  @DisplayName("insert는 독립 트랜잭션(REQUIRES_NEW)으로 커밋되어 즉시 조회된다")
  void insert_commitsInOwnTransaction() {
    // When
    DeviceTokenResponse response = deviceTokenWriter.insert(userId, request());

    // Then
    assertThat(response.id()).isNotNull();
    assertThat(deviceTokenRepository.findByUserIdAndToken(userId, token)).isPresent();
  }

  @Test
  @DisplayName("같은 (user, token) 재삽입은 UNIQUE 위반을 던지고 행은 1개만 유지된다(BC1)")
  void insert_duplicate_throwsAndKeepsSingleRow() {
    // Given
    deviceTokenWriter.insert(userId, request());

    // When & Then
    assertThatThrownBy(() -> deviceTokenWriter.insert(userId, request()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(deviceTokenRepository.findByUserId(userId)).hasSize(1);
  }

  @Test
  @DisplayName("REQUIRES_NEW라 insert의 UNIQUE 위반이 바깥 트랜잭션을 오염시키지 않아 재조회가 가능하다(BC2)")
  void insert_violationDoesNotPoisonOuterTransaction() {
    // Given — 경쟁 승자 행을 먼저 커밋해 둔다
    deviceTokenWriter.insert(userId, request());

    // When — 바깥 트랜잭션 안에서 중복 insert가 터져도(REQUIRES_NEW로 격리) 이어서 조회가 성공해야 한다
    TransactionTemplate outer = new TransactionTemplate(transactionManager);
    boolean survived = Boolean.TRUE.equals(outer.execute(status -> {
      assertThatThrownBy(() -> deviceTokenWriter.insert(userId, request()))
          .isInstanceOf(DataIntegrityViolationException.class);
      // 바깥 트랜잭션이 오염됐다면 이 조회가 "current transaction is aborted"로 실패한다.
      return deviceTokenRepository.findByUserIdAndToken(userId, token).isPresent();
    }));

    // Then
    assertThat(survived).isTrue();
  }
}
