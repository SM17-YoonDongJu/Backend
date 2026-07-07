package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * USERS Aggregate Root. 회원 계정 정보를 관리한다.
 *
 * <p>소셜 계정(provider/providerUserId)은 별도 Aggregate(SocialAccount)로 분리하고
 * user_id(UUID)로만 연결한다. 신규 가입은 {@link #create(String, String, Role)}로만 만든다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "nickname", nullable = false, unique = true)
  private String nickname;

  @Column(name = "email")
  private String email;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 30)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "gender", length = 10)
  private String gender;

  @Column(name = "region")
  private String region;

  @Column(name = "birth_date")
  private LocalDate birthDate;

  @Column(name = "avatar_url")
  private String avatarUrl;

  /**
   * 소셜 로그인 신규 가입 시 사용하는 정적 팩터리. 상태는 ACTIVE로 시작하고,
   * email이 있으면 verified로 간주한다.
   */
  public static User create(String nickname, String email, Role role) {
    User user = new User();
    user.nickname = nickname;
    user.email = email;
    user.emailVerified = email != null && !email.isBlank();
    user.role = role;
    user.status = UserStatus.ACTIVE;
    return user;
  }
}
