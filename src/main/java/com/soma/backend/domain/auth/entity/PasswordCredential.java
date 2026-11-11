package com.soma.backend.domain.auth.entity;

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * PASSWORD_CREDENTIALS Aggregate Root. 아이디·비밀번호 일반 로그인용 자격증명이다.
 *
 * <p>{@link com.soma.backend.domain.auth.entity.SocialAccount}와 같은 방식으로 User Aggregate와는
 * user_id(UUID)로만 참조한다(객체 참조 금지). 한 회원당 하나만 존재하며(user_id UNIQUE),
 * {@code loginId}로 로그인 시 회원을 식별한다.
 *
 * <p>평문 비밀번호는 이 엔티티에 들어오지 않는다 — 해시만 보관한다. 해시 대조를 엔티티 메서드로 두지
 * 않은 이유는 {@code PasswordEncoder}가 엔티티가 의존해선 안 되는 바깥 레이어 타입이기 때문이다
 * (의존 방향은 항상 안쪽). 대조는 서비스가 encoder로 수행한다.
 */
@Entity
@Table(name = "password_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordCredential extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  /** 로그인 아이디. 대소문자를 구분하지 않도록 {@link #normalizeLoginId}로 정규화한 값만 저장된다. */
  @Column(name = "login_id", nullable = false, unique = true, length = 30)
  private String loginId;

  /**
   * 비밀번호 해시(BCrypt). 평문은 저장하지 않으며, 입력값과의 대조는 이 엔티티가 아니라 서비스가
   * {@code PasswordEncoder.matches(평문, 이 값)}로 수행한다(엔티티는 크립토 컴포넌트를 참조하지 않는다).
   */
  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  /**
   * 자격증명을 생성한다. 유일한 생성 경로이며 {@code loginId}는 항상 정규화해서 보관한다
   * (호출자가 정규화를 빠뜨려도 대소문자만 다른 중복 계정이 생기지 않게 한다).
   *
   * <p>{@code passwordHash}에는 반드시 인코더로 해싱한 값을 넘긴다 — 평문을 넘기면 그대로 저장된다.
   */
  public static PasswordCredential create(UUID userId, String loginId, String passwordHash) {
    PasswordCredential credential = new PasswordCredential();
    credential.userId = userId;
    credential.loginId = normalizeLoginId(loginId);
    credential.passwordHash = passwordHash;
    return credential;
  }

  /**
   * 로그인 아이디 정규화 규칙: 앞뒤 공백을 제거하고 소문자로 낮춘다. "아이디는 대소문자를 구분하지 않는다"는
   * 도메인 규칙이라 서비스가 아니라 Aggregate가 소유한다 — 가입(중복 검사·저장)과 로그인(조회)이 같은
   * 규칙을 써야 하는데, 서비스끼리는 서로를 호출할 수 없어(레이어 규칙) 여기서 공유한다.
   *
   * <p>{@link Locale#ROOT}로 낮춘다 — 기본 로케일을 쓰면 터키어 환경에서 {@code I}가 점 없는 {@code ı}로
   * 바뀌어 같은 아이디가 서버 로케일에 따라 다르게 정규화된다.
   */
  public static String normalizeLoginId(String loginId) {
    return loginId.strip().toLowerCase(Locale.ROOT);
  }
}
