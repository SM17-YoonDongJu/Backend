package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * USER_INSURANCES 테스트 픽스처.
 *
 * <p>{@link UserInsurance}는 쓰기 경로(POST /users/me/insurances)가 아직 없는 읽기 모델이라 도메인 팩터리가
 * 없다. 프로덕션 코드에 테스트 전용 생성자를 뚫지 않기 위해, 같은 패키지의 픽스처가 protected 기본 생성자로
 * 인스턴스를 만들고 리플렉션으로 필드를 채운다. 쓰기 유스케이스가 생기면 이 픽스처는 도메인 팩터리 호출로
 * 대체한다.
 */
public final class UserInsuranceFixture {

  private UserInsuranceFixture() {
  }

  public static UserInsurance of(
      UUID userId,
      String insurerName,
      String productName,
      @Nullable String policyNo,
      @Nullable LocalDate enrolledAt,
      @Nullable List<String> coverages,
      @Nullable String policyFileUrl) {
    UserInsurance insurance = new UserInsurance();
    ReflectionTestUtils.setField(insurance, "userId", userId);
    ReflectionTestUtils.setField(insurance, "insurerName", insurerName);
    ReflectionTestUtils.setField(insurance, "productName", productName);
    ReflectionTestUtils.setField(insurance, "policyNo", policyNo);
    ReflectionTestUtils.setField(insurance, "enrolledAt", enrolledAt);
    ReflectionTestUtils.setField(insurance, "coverages", coverages);
    ReflectionTestUtils.setField(insurance, "policyFileUrl", policyFileUrl);
    return insurance;
  }

  /**
   * 테스트 프로파일({@code ddl-auto: create-drop})은 엔티티 매핑으로 스키마를 만드는데,
   * {@code created_at}이 {@code insertable = false}라 INSERT에서 빠지고 Hibernate가 생성한 컬럼에는
   * DB 기본값이 없어 NOT NULL 위반이 난다. 운영 스키마(Flyway)는 {@code DEFAULT now()}를 가지므로
   * 테스트에서만 같은 기본값을 보강한다.
   */
  public static String createdAtDefaultDdl() {
    return "ALTER TABLE user_insurances ALTER COLUMN created_at SET DEFAULT now()";
  }
}
