package com.soma.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;

/**
 * 디바이스 토큰 등록·해제 유스케이스. 모든 조작은 principal 본인(userId) 소유 토큰만 대상으로 한다.
 * 등록은 멱등(upsert)이고 해제도 멱등(없는 토큰 삭제는 no-op)이라 재요청·로그아웃 반복에 안전하다.
 */
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

  private final DeviceTokenRepository deviceTokenRepository;
  private final DeviceTokenWriter deviceTokenWriter;

  /**
   * 디바이스 토큰을 등록한다(멱등 upsert). 이미 같은 (user, token)이 있으면 기존 행을 반환하고,
   * 없으면 신규 저장한다. 신규 저장은 독립 트랜잭션(REQUIRES_NEW)으로 위임하므로, 동시 등록 경쟁으로
   * UNIQUE 위반이 나도 바깥 트랜잭션은 오염되지 않는다 — 위반을 잡아 재조회(경쟁 승자 행)로 흡수해 200을 유지한다.
   */
  @Transactional
  public DeviceTokenResponse register(UUID userId, RegisterDeviceTokenRequest request) {
    return deviceTokenRepository.findByUserIdAndToken(userId, request.token())
        .map(DeviceTokenResponse::from)
        .orElseGet(() -> insertOrGet(userId, request));
  }

  private DeviceTokenResponse insertOrGet(UUID userId, RegisterDeviceTokenRequest request) {
    try {
      return deviceTokenWriter.insert(userId, request);
    } catch (DataIntegrityViolationException ex) {
      // 동시 등록 경쟁: 다른 트랜잭션이 먼저 같은 (user, token)을 커밋해 UNIQUE 위반.
      // READ COMMITTED(PostgreSQL 기본)에서 재조회는 승자 행을 보므로, 그 행을 반환해 멱등성을 지킨다.
      return deviceTokenRepository.findByUserIdAndToken(userId, request.token())
          .map(DeviceTokenResponse::from)
          .orElseThrow(() -> ex);
    }
  }

  /** 디바이스 토큰을 해제한다(멱등). 본인 소유 (user, token)만 삭제하고, 없어도 no-op이다. */
  @Transactional
  public void deregister(UUID userId, String token) {
    deviceTokenRepository.deleteByUserIdAndToken(userId, token);
  }
}
