package com.soma.backend.devtools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * K6AdjusterSeedRunner 통합 테스트. 러너는 {@code @Profile("!test")}라 테스트 컨텍스트에 빈으로 뜨지 않으므로
 * (그게 의도다 — 136개 테스트 컨텍스트 오염 방지) 여기서 {@code enabled=true}로 직접 생성해 실행한다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — 러너가 리포지토리 호출 단위로 커밋하는 실제 동작(재실행
 * idempotency·부분 상태 복구)을 관찰해야 하기 때문이다. 생성된 행은 {@code @BeforeEach}/{@code @AfterEach}에서
 * 닉네임 접두어 기준으로 정리한다(FK 때문에 adjuster_profiles → users 순서). 로컬 docker PostgreSQL(test_db) 필요.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("K6AdjusterSeedRunner 통합 테스트 (k6 부하테스트용 사정사 시딩)")
class K6AdjusterSeedRunnerTest {

  private static final int EXPECTED_COUNT = 10;
  private static final String NICKNAME_PATTERN = "k6-adjuster-%";

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private K6AdjusterSeedRunner runner;

  @BeforeEach
  void setUp() {
    runner = new K6AdjusterSeedRunner(true, userRepository, adjusterProfileRepository, jdbcTemplate);
    deleteSeededRows();
  }

  @AfterEach
  void tearDown() {
    deleteSeededRows();
  }

  @Test
  @DisplayName("1회 실행하면 k6-adjuster-1..10 User 10명과 프로필 10건이 생성된다")
  void run_seedsTenAdjustersWithProfiles() {
    // When
    runner.run(null);

    // Then
    assertThat(countSeededUsers()).isEqualTo(EXPECTED_COUNT);
    assertThat(countSeededProfiles()).isEqualTo(EXPECTED_COUNT);

    List<User> users = seededUsers();
    assertThat(users).hasSize(EXPECTED_COUNT);
    assertThat(users).allSatisfy(user -> {
      assertThat(user.getRole()).isEqualTo(Role.CERTIFICATED_ADJUSTER);
      // users.phone_number_hmac은 UNIQUE라 더미 값을 넣으면 두 번째 계정부터 충돌한다. 항상 짝으로 null.
      assertThat(user.getPhoneNumber()).isNull();
      assertThat(user.getPhoneNumberHmac()).isNull();
      assertThat(adjusterProfileRepository.findByUserId(user.getId())).isPresent();
    });
  }

  @Test
  @DisplayName("프로필에 매칭·정렬에 필요한 최소 유효값(전문분야 키워드·평점·후기수)이 채워진다")
  void run_fillsProfileValuesUsedByMatchingAndSorting() {
    // When
    runner.run(null);

    // Then
    UUID userId = seededUsers().getFirst().getId();
    AdjusterProfile profile = adjusterProfileRepository.findByUserId(userId).orElseThrow();

    // PendingReviewQueryService.SPECIALTY_MATCH_KEYWORD가 "실손"·"교통사고" 포함 여부로 매칭한다.
    assertThat(profile.getSpecialties()).anyMatch(specialty -> specialty.contains("실손"));
    assertThat(profile.getSpecialties()).anyMatch(specialty -> specialty.contains("교통사고"));
    // rating_mean만 넣고 review_count가 0이면 마이페이지 평점이 0으로 깎인다 — 둘 다 채워야 한다.
    assertThat(profile.getRatingMean()).isNotNull();
    assertThat(profile.getRatingMean().doubleValue()).isEqualTo(4.5);
    assertThat(profile.getReviewCount()).isEqualTo(10);
    assertThat(profile.getActivityRegion()).containsExactly("서울");
    assertThat(profile.getConsultMethods()).containsExactly("채팅");
    assertThat(profile.getName()).isNotBlank();
    assertThat(profile.getHeadline()).isNotBlank();
  }

  @Test
  @DisplayName("2회 실행해도 증분이 0이다 — users.nickname은 UNIQUE가 아니라 중복 방지는 애플리케이션 책임이다")
  void run_isIdempotent() {
    // Given
    runner.run(null);
    List<UUID> firstRunUserIds = seededUsers().stream().map(User::getId).toList();

    // When
    runner.run(null);

    // Then
    assertThat(countSeededUsers()).isEqualTo(EXPECTED_COUNT);
    assertThat(countSeededProfiles()).isEqualTo(EXPECTED_COUNT);
    // 중복 행이 생기면 findByNickname(Optional 반환)이 IncorrectResultSizeDataAccessException을 던져
    // 그 닉네임으로는 dev 로그인이 영구히 깨진다. seededUsers()가 이 조회를 그대로 태운다.
    assertThat(seededUsers().stream().map(User::getId).toList())
        .containsExactlyElementsOf(firstRunUserIds);
  }

  @Test
  @DisplayName("User만 남고 프로필이 없는 부분 상태에서 재실행하면 프로필만 다시 생성된다")
  void run_recoversPartialState() {
    // Given
    runner.run(null);
    List<UUID> userIds = seededUsers().stream().map(User::getId).toList();
    deleteSeededProfiles();
    assertThat(countSeededProfiles()).isZero();

    // When
    runner.run(null);

    // Then
    assertThat(countSeededUsers()).isEqualTo(EXPECTED_COUNT);
    assertThat(seededUsers().stream().map(User::getId).toList()).containsExactlyElementsOf(userIds);
    assertThat(countSeededProfiles()).isEqualTo(EXPECTED_COUNT);
  }

  @Test
  @DisplayName("license_no가 10건 모두 서로 다르다 — test_db에는 UNIQUE 제약이 없어 값 자체를 검증해야 한다")
  void run_assignsDistinctLicenseNumbers() {
    // When
    runner.run(null);

    // Then
    List<String> licenseNumbers = jdbcTemplate.queryForList(
        "SELECT ap.license_no FROM adjuster_profiles ap JOIN users us ON us.id = ap.user_id "
            + "WHERE us.nickname LIKE ? ORDER BY ap.license_no",
        String.class, NICKNAME_PATTERN);

    assertThat(licenseNumbers).hasSize(EXPECTED_COUNT);
    assertThat(licenseNumbers).doesNotHaveDuplicates();
    assertThat(licenseNumbers).containsExactly(
        "K6-2026-001", "K6-2026-002", "K6-2026-003", "K6-2026-004", "K6-2026-005",
        "K6-2026-006", "K6-2026-007", "K6-2026-008", "K6-2026-009", "K6-2026-010");
  }

  private List<User> seededUsers() {
    return IntStream.rangeClosed(1, EXPECTED_COUNT)
        .mapToObj(seq -> userRepository.findByNickname("k6-adjuster-" + seq))
        .flatMap(Optional::stream)
        .toList();
  }

  private int countSeededUsers() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE nickname LIKE ?", Integer.class, NICKNAME_PATTERN);
    return count == null ? 0 : count;
  }

  private int countSeededProfiles() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM adjuster_profiles ap JOIN users us ON us.id = ap.user_id "
            + "WHERE us.nickname LIKE ?",
        Integer.class, NICKNAME_PATTERN);
    return count == null ? 0 : count;
  }

  /** FK(adjuster_profiles.user_id → users.id) 때문에 프로필을 먼저 지운다. */
  private void deleteSeededRows() {
    deleteSeededProfiles();
    jdbcTemplate.update("DELETE FROM users WHERE nickname LIKE ?", NICKNAME_PATTERN);
  }

  private void deleteSeededProfiles() {
    jdbcTemplate.update(
        "DELETE FROM adjuster_profiles WHERE user_id IN (SELECT id FROM users WHERE nickname LIKE ?)",
        NICKNAME_PATTERN);
  }
}
