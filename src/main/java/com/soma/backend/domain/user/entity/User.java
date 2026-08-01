package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

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
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * USERS Aggregate Root. 회원 계정 정보를 관리한다.
 *
 * <p>소셜 계정(provider/providerUserId)은 별도 Aggregate(SocialAccount)로 분리하고
 * user_id(UUID)로만 연결한다. 신규 가입은 {@link #create}로만 만든다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

  /** 탈퇴 시 이름(실명)을 대체하는 익명화 값. */
  private static final String WITHDRAWN_NICKNAME = "탈퇴회원";

  /** 탈퇴 시 생년월일(개인정보)을 대체하는 익명화 값. birth_date가 NOT NULL이라 null 대신 상수로 마스킹한다. */
  private static final LocalDate WITHDRAWN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

  @Id
  @GeneratedValue
  private UUID id;

  /** 이름(실명). 동명이인이 가능하므로 UNIQUE를 두지 않는다. */
  @Column(name = "nickname", nullable = false)
  private String nickname;

  @Column(name = "phone_number", unique = true)
  private String phoneNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 30)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "gender", nullable = false, length = 10)
  private String gender;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "region", columnDefinition = "text[]")
  private List<String> region;

  @Column(name = "birth_date", nullable = false)
  private LocalDate birthDate;

  @Column(name = "avatar_url")
  private String avatarUrl;

  /**
   * 소셜 로그인 신규 가입 시 사용하는 정적 팩터리. 이름·생년월일·성별·전화번호를 받고
   * 상태는 ACTIVE로 시작한다.
   */
  public static User create(
      String nickname, LocalDate birthDate, String gender, String phoneNumber, Role role) {
    User user = new User();
    user.nickname = nickname;
    user.birthDate = birthDate;
    user.gender = gender;
    user.phoneNumber = phoneNumber;
    user.role = role;
    user.status = UserStatus.ACTIVE;
    return user;
  }

  /**
   * 프로필(전화번호·지역·프로필사진)을 부분 수정한다. {@code null} 인자는 변경하지 않는다(부분 수정).
   * 번호 유일성 검사는 저장소를 아는 서비스가 호출 전에 수행한다.
   */
  public void updateProfile(
      @Nullable String phoneNumber, @Nullable List<String> region, @Nullable String avatarUrl) {
    if (phoneNumber != null) {
      this.phoneNumber = phoneNumber;
    }
    if (region != null) {
      this.region = region;
    }
    if (avatarUrl != null) {
      this.avatarUrl = avatarUrl;
    }
  }

  /**
   * 회원 탈퇴(soft delete). 상태를 WITHDRAWN으로 바꾸고 개인정보(연락처·이름·지역·성별·생년월일·프로필사진)를
   * 파기한다. 전화번호를 {@code null}로 비워 UNIQUE 번호를 해제하므로 동일 번호로 재가입할 수 있다.
   * birth_date는 NOT NULL이라 null 대신 마스킹 값({@link #WITHDRAWN_BIRTH_DATE})으로 대체한다.
   */
  public void withdraw() {
    this.status = UserStatus.WITHDRAWN;
    this.phoneNumber = null;
    this.nickname = WITHDRAWN_NICKNAME;
    this.region = null;
    this.gender = "";
    this.birthDate = WITHDRAWN_BIRTH_DATE;
    this.avatarUrl = null;
  }

  /**
   * 이미 탈퇴한 계정인지 여부.
   */
  public boolean isWithdrawn() {
    return this.status == UserStatus.WITHDRAWN;
  }

  /**
   * 손해사정사 자격 신청 접수에 따른 역할 전이. USER는 UNCERTIFICATED_ADJUSTER로 전이하고,
   * 이미 UNCERTIFICATED_ADJUSTER(반려·심사 중 신청 이력)면 재신청으로 보아 그대로 둔다(멱등).
   * 이미 인증 사정사(CERTIFICATED_ADJUSTER)거나 관리자(ADMIN)면 신청 대상이 아니므로
   * {@code DUPLICATE_RESOURCE}를 던진다.
   */
  public void applyForAdjuster() {
    if (this.role == Role.CERTIFICATED_ADJUSTER || this.role == Role.ADMIN) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    this.role = Role.UNCERTIFICATED_ADJUSTER;
  }
}
