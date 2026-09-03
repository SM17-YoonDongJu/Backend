package com.soma.backend.devtools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * k6 부하테스트용 손해사정사 계정({@code k6-adjuster-1} ~ {@code k6-adjuster-10})을 1회성으로 시딩하는
 * 임시 러너. dev 환경에서 {@code POST /auth/dev/login}이 닉네임만으로 토큰을 내주므로, 사정사 역할이
 * 필요한 시나리오(공개 목록·검수대기·채택)를 부하테스트하려면 그 닉네임의 계정이 미리 존재해야 한다.
 * {@code DevMockDataSeedRunner}·{@code PiiHmacIndexBackfillRunner} 선례와 동일하게 property로 게이트하고,
 * 로드테스트 창이 끝나면 후속 커밋에서 이 클래스와 시딩된 계정을 함께 정리한다.
 *
 * <p>게이트 프로퍼티 {@code app.dev-seed.k6-adjusters-enabled}는 <b>어떤 application*.yml에도 선언하지
 * 않는다</b>(선례 {@code app.dev-seed.enabled}와 동일). 기본값 {@code false}로 꺼져 있고, 필요할 때만
 * {@code SPRING_APPLICATION_JSON}이나 compose {@code environment:}로 1회 주입해 켠다.
 *
 * <p>시딩 데이터는 전부 합성값이라 개인정보가 아니다. 전화번호는 {@code phoneNumber}/{@code phoneNumberHmac}
 * 둘 다 {@code null}로 둔다 — {@code users.phone_number_hmac}이 UNIQUE라 더미 값을 넣으면 두 번째 계정부터
 * 충돌하고, 두 필드는 항상 짝을 이뤄야 한다({@code User.create} javadoc 규약).
 *
 * <p>{@code @Transactional}을 붙이지 않는다. {@code ApplicationRunner.run()}에서 자기 자신의 트랜잭션
 * 메서드를 호출하면 프록시를 타지 않아 트랜잭션이 열리지 않고(self-invocation), 클래스 단위로 묶으면 1명
 * 실패에 10명이 전부 롤백된다. 리포지토리 호출 단위 커밋에 맡겨 사정사 1명당 User 1커밋 + Profile 1커밋으로
 * 두고, 재실행 시 idempotent 체크가 빈 곳만 채우게 한다.
 */
@Slf4j
@Component
@Profile("!test")
public class K6AdjusterSeedRunner implements ApplicationRunner {

  /** 시딩할 사정사 수. 닉네임은 {@code k6-adjuster-1} ~ {@code k6-adjuster-10}. */
  private static final int ADJUSTER_COUNT = 10;

  private static final String NICKNAME_PREFIX = "k6-adjuster-";

  /** 합성 생년월일(실제 개인정보 아님). {@code users.birth_date}가 NOT NULL이라 값이 필요하다. */
  private static final LocalDate BIRTH_DATE = LocalDate.of(1985, 1, 1);

  /** {@code users.gender}는 NOT NULL(V5). */
  private static final String GENDER = "MALE";

  private static final String REGION = "서울";

  /**
   * 전문분야. {@code PendingReviewQueryService.SPECIALTY_MATCH_KEYWORD}가 "실손"·"교통사고" 포함 여부로
   * 검수대기 전문분야 매칭을 판정하므로 두 키워드를 포함시킨다(미포함이면 매칭 경로가 부하테스트에서 아예
   * 실행되지 않는다). 목록 필터 {@code specialty}(array_contains)도 이 값을 본다.
   */
  private static final List<String> SPECIALTIES = List.of("실손보험", "교통사고");

  private static final List<String> CONSULT_METHODS = List.of("채팅");

  /**
   * 목록 기본 정렬이 {@code ratingMean desc nullsLast}라 null이면 시드 계정이 전부 뒤로 밀린다. 그리고
   * {@code AdjusterMyPageResponse.resolveAverageRating}이 {@code reviewCount == 0}이면 평점을 0.0으로
   * 깎으므로 {@code REVIEW_COUNT}와 반드시 함께 채운다.
   */
  private static final BigDecimal RATING_MEAN = new BigDecimal("4.5");

  private static final int REVIEW_COUNT = 10;

  private static final int CAREER_YEARS = 5;

  private static final int COMPLETED_CONSULT_COUNT = 12;

  private final boolean enabled;
  private final UserRepository userRepository;
  private final AdjusterProfileRepository adjusterProfileRepository;
  private final JdbcTemplate jdbcTemplate;

  public K6AdjusterSeedRunner(
      @Value("${app.dev-seed.k6-adjusters-enabled:false}") boolean enabled,
      UserRepository userRepository,
      AdjusterProfileRepository adjusterProfileRepository,
      JdbcTemplate jdbcTemplate) {
    this.enabled = enabled;
    this.userRepository = userRepository;
    this.adjusterProfileRepository = adjusterProfileRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    int createdUsers = 0;
    int createdProfiles = 0;

    for (int seq = 1; seq <= ADJUSTER_COUNT; seq++) {
      String nickname = NICKNAME_PREFIX + seq;

      // USERS와 ADJUSTER_PROFILES를 독립적으로 확인한다 — 이전 실행이 중간에 죽어 User만 남고 Profile이
      // 없는 부분 상태도 재실행으로 복구된다. users.nickname은 UNIQUE가 아니므로(V1의 제약을 V2에서 제거)
      // 중복 생성 방지는 전적으로 애플리케이션 책임이다. 중복 행이 생기면 findByNickname이
      // IncorrectResultSizeDataAccessException을 던져 그 닉네임으로는 dev 로그인이 영구히 깨진다.
      User user = userRepository.findByNickname(nickname).orElse(null);
      if (user == null) {
        user = userRepository.save(User.create(
            nickname, BIRTH_DATE, GENDER, null, null, Role.CERTIFICATED_ADJUSTER, List.of(REGION)));
        createdUsers++;
      }

      if (adjusterProfileRepository.findByUserId(user.getId()).isEmpty()) {
        insertAdjusterProfile(user.getId(), seq, now);
        createdProfiles++;
      }
    }

    log.info("k6 사정사 시딩 완료 — users created={}, skipped={} / profiles created={}, skipped={}",
        createdUsers, ADJUSTER_COUNT - createdUsers, createdProfiles, ADJUSTER_COUNT - createdProfiles);
  }

  /**
   * ADJUSTER_PROFILES 행을 raw SQL로 삽입한다({@code DevMockDataSeedRunner}가 명문화한 예외 규칙과 동일
   * 근거). {@code AdjusterProfile}에는 정식 쓰기 경로가 아예 없고(protected 기본 생성자 + 부분 수정
   * {@code updateProfile()}만) 이 테이블에는 PII 암호화 대상 컬럼이 하나도 없다 — 두 조건을 모두 충족하므로
   * raw INSERT가 안전하다. 삭제될 1회성 러너를 위해 프로덕션 Aggregate에 영구 팩터리를 남기지 않는다.
   *
   * <p>테이블명은 스키마를 지정하지 않는다 — dev/prod는 JDBC URL {@code ?currentSchema=core,public}으로
   * {@code core}가, test_db는 {@code public}이 해석된다(선례와 동일). DDL이 아니라 DML이라 "신규 테이블은
   * {@code core.} 명시" 규칙과는 무관하다.
   *
   * <p><b>license_no는 반드시 seq별로 유일해야 한다.</b> {@code adjuster_profiles.license_no}는 V1 DDL에만
   * UNIQUE가 있고 엔티티에는 {@code unique = true}가 없다 — test_db는 {@code ddl-auto: create-drop}으로
   * 엔티티 매핑에서 스키마를 만들어 제약이 존재하지 않으므로, 값을 공유해도 테스트는 통과하고 dev 배포에서만
   * 두 번째 INSERT부터 터진다.
   */
  private void insertAdjusterProfile(UUID userId, int seq, LocalDateTime now) {
    jdbcTemplate.update(
        "INSERT INTO adjuster_profiles ("
            + "id, user_id, license_no, name, headline, specialties, career, cases_accepted, "
            + "cases_reviewed, completed_consult_count, rating_mean, review_count, consult_methods, "
            + "activity_region, verified_at, introduction, created_at, updated_at"
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, userId);
          ps.setString(3, licenseNo(seq));
          ps.setString(4, "k6-사정사" + seq);
          ps.setString(5, "부하테스트용 손해사정사");
          ps.setArray(6, ps.getConnection().createArrayOf("text", SPECIALTIES.toArray()));
          ps.setInt(7, CAREER_YEARS);
          ps.setInt(8, 0);
          ps.setInt(9, 0);
          ps.setInt(10, COMPLETED_CONSULT_COUNT);
          ps.setBigDecimal(11, RATING_MEAN);
          ps.setInt(12, REVIEW_COUNT);
          ps.setArray(13, ps.getConnection().createArrayOf("text", CONSULT_METHODS.toArray()));
          ps.setArray(14, ps.getConnection().createArrayOf("text", new Object[] {REGION}));
          ps.setObject(15, now);
          ps.setString(16, "k6 부하테스트용으로 생성된 계정입니다.");
          ps.setObject(17, now);
          ps.setObject(18, now);
        });
  }

  /** {@code adjuster_profiles.license_no}(DDL UNIQUE)용 seq별 유일 값. */
  private static String licenseNo(int seq) {
    return String.format(Locale.ROOT, "K6-2026-%03d", seq);
  }
}
