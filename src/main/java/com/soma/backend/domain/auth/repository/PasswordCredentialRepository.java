package com.soma.backend.domain.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.auth.entity.PasswordCredential;

/**
 * 아이디·비밀번호 자격증명 저장소. 조회는 전부 파생 쿼리이며, 넘기는 {@code loginId}는 호출자가
 * {@code PasswordCredential.normalizeLoginId}로 정규화한 값이어야 한다(저장값이 정규화된 형태라서).
 */
public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {

  Optional<PasswordCredential> findByLoginId(String loginId);

  boolean existsByLoginId(String loginId);

  /**
   * 회원 탈퇴 시 자격증명을 파기한다. 남겨 두면 비밀번호 해시가 보존되고(탈퇴 시 개인정보 파기 정책 위반)
   * {@code login_id} UNIQUE가 영구 점유돼 같은 아이디로 재가입할 수 없다 — 탈퇴 시 전화번호 UNIQUE를
   * 풀어 재가입을 허용하는 {@code User.withdraw} 규약과 같은 이유로 지운다.
   */
  void deleteByUserId(UUID userId);
}
