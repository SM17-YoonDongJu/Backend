package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import com.soma.backend.global.security.crypto.converter.UserPhoneNumberConverter;

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

  /**
   * 생년월일을 입력받지 않는 가입 경로에서 쓰는 '미상' 센티넬. birth_date가 NOT NULL이라 null을 넣을 수 없어
   * 상수로 채운다. {@link #WITHDRAWN_BIRTH_DATE}와 날짜는 같지만 의미가 달라(탈퇴 마스킹 vs 미입력) 별도
   * 상수로 선언한다 — 한쪽 정책이 바뀌어도 다른 쪽이 따라 움직이지 않게 한다.
   */
  private static final LocalDate UNKNOWN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

  /** 성별을 입력받지 않는 가입 경로에서 쓰는 미입력 값. gender는 NOT NULL이지만 빈 문자열을 허용한다(V5). */
  private static final String UNKNOWN_GENDER = "";

  @Id
  @GeneratedValue
  private UUID id;

  /** 이름(실명). 동명이인이 가능하므로 UNIQUE를 두지 않는다. */
  @Column(name = "nickname", nullable = false)
  private String nickname;

  /** AES-256-GCM 암호화(봉투). AAD=users:phone_number. 조회는 이 필드가 아니라 {@link #phoneNumberHmac}로 한다. */
  @Convert(converter = UserPhoneNumberConverter.class)
  @Column(name = "phone_number")
  private String phoneNumber;

  /** phoneNumber의 HMAC-SHA256 블라인드 인덱스(조회 전용, 이슈 #232). 엔티티가 직접 계산하지 못해 서비스가 넘긴다. */
  @Column(name = "phone_number_hmac", unique = true)
  private byte[] phoneNumberHmac;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 30)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "gender", nullable = false, length = 10)
  private String gender;

  /** 활동/거주 지역. 가입 시 선택 입력이며 이후 {@link #updateProfile}로 수정한다. 미입력은 null(빈 배열 아님). */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "region", columnDefinition = "text[]")
  private List<String> region;

  @Column(name = "birth_date", nullable = false)
  private LocalDate birthDate;

  @Column(name = "avatar_url")
  private String avatarUrl;

  /**
   * 소셜 로그인 신규 가입 시 사용하는 정적 팩터리. 이름·생년월일·성별·전화번호·지역을 받고
   * 상태는 ACTIVE로 시작한다. 지역은 선택 입력이며 비어 있으면 빈 배열 대신 {@code null}로
   * 정규화한다(탈퇴 시 region을 null로 파기하는 규약과 동일한 "미입력=null" 상태만 갖는다).
   *
   * <p>{@code phoneNumberHmac}은 엔티티가 직접 계산하지 못해(크립토 컴포넌트를 참조하지 않음) 서비스가
   * {@code PiiHmac}으로 미리 계산해 넘긴다 — {@code phoneNumber}가 {@code null}이면 이 값도 {@code null}이어야
   * 한다(둘은 항상 짝을 이룬다).
   */
  public static User create(
      String nickname,
      LocalDate birthDate,
      String gender,
      String phoneNumber,
      byte[] phoneNumberHmac,
      Role role,
      @Nullable List<String> region) {
    User user = new User();
    user.nickname = nickname;
    user.birthDate = birthDate;
    user.gender = gender;
    user.phoneNumber = phoneNumber;
    user.phoneNumberHmac = phoneNumberHmac;
    user.role = role;
    user.status = UserStatus.ACTIVE;
    user.region = (region == null || region.isEmpty()) ? null : List.copyOf(region);
    return user;
  }

  /**
   * 프로필 입력(생년월일·성별·전화번호·지역)을 받지 않는 가입 경로용 정적 팩터리. 아이디·비밀번호
   * 일반 로그인 가입(이슈 #389)이 쓴다. 상태는 ACTIVE로 시작하고 역할은 호출자가 지정한다.
   *
   * <p>NOT NULL 컬럼인 birth_date·gender는 비울 수 없어 '미상' 센티넬로 채운다
   * ({@link #UNKNOWN_BIRTH_DATE}·{@link #UNKNOWN_GENDER}). 따라서 <b>사정사 검수 화면에 생년월일이
   * 1900-01-01로 보이는 것은 버그가 아니라 의도된 미상 표기</b>다 — 생년월일을 읽는 곳은 그 화면뿐이고
   * 나이 계산 없이 값을 그대로 표시하므로 센티넬이 로직을 깨지 않는다.
   *
   * <p>전화번호(와 짝인 HMAC 인덱스)·지역은 {@code null}로 둔다. 번호가 null이면 UNIQUE 제약에 걸리지
   * 않으므로 프로필 미입력 회원이 여럿 생겨도 충돌하지 않으며, 이후 {@link #updateProfile}로 채운다.
   */
  public static User createWithoutProfile(String nickname, Role role) {
    User user = new User();
    user.nickname = nickname;
    user.birthDate = UNKNOWN_BIRTH_DATE;
    user.gender = UNKNOWN_GENDER;
    user.phoneNumber = null;
    user.phoneNumberHmac = null;
    user.role = role;
    user.status = UserStatus.ACTIVE;
    user.region = null;
    return user;
  }

  /**
   * 프로필(전화번호·지역·프로필사진)을 부분 수정한다. {@code null} 인자는 변경하지 않는다(부분 수정).
   * 번호 유일성 검사·{@code phoneNumberHmac} 계산은 저장소·크립토 컴포넌트를 아는 서비스가 호출 전에 수행한다.
   */
  public void updateProfile(
      @Nullable String phoneNumber, @Nullable byte[] phoneNumberHmac,
      @Nullable List<String> region, @Nullable String avatarUrl) {
    if (phoneNumber != null) {
      this.phoneNumber = phoneNumber;
      this.phoneNumberHmac = phoneNumberHmac;
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
   * 파기한다. 전화번호(및 HMAC 인덱스)를 {@code null}로 비워 UNIQUE 번호를 해제하므로 동일 번호로 재가입할 수
   * 있다. birth_date는 NOT NULL이라 null 대신 마스킹 값({@link #WITHDRAWN_BIRTH_DATE})으로 대체한다.
   */
  public void withdraw() {
    this.status = UserStatus.WITHDRAWN;
    this.phoneNumber = null;
    this.phoneNumberHmac = null;
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
