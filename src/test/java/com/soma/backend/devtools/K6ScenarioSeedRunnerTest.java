package com.soma.backend.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.soma.backend.domain.user.repository.UserRepository;

/**
 * K6ScenarioSeedRunner 단위 테스트. 러너는 {@code @Profile("!test & !prod")}라 테스트 컨텍스트에 빈으로
 * 뜨지 않으므로({@code @SpringBootTest}로는 잡을 수 없다) 생성자를 직접 호출하는 순수 단위 테스트로 둔다.
 *
 * <p>이 1회성 러너의 유일한 회귀 위험은 <b>게이트 사고</b>다 — 폭발 반경이 약 33,700행이라 게이트가
 * 새면 dev DB가 통째로 오염된다. 그래서 (1) 게이트가 꺼져 있을 때, (2) 선행 사정사 시딩이 없을 때
 * 쓰기가 한 번도 일어나지 않는지만 검증한다(시딩 결과 자체는 dev 실행 로그로 확인한다).
 */
@DisplayName("K6ScenarioSeedRunner 단위 테스트 (게이트 차단)")
class K6ScenarioSeedRunnerTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
  private final K6ScenarioSeedWriter writer = mock(K6ScenarioSeedWriter.class);

  @Test
  @DisplayName("게이트가 꺼져 있으면 리포지토리·JdbcTemplate·Writer를 한 번도 건드리지 않는다")
  void run_doesNothingWhenGateIsOff() {
    // Given
    K6ScenarioSeedRunner runner = newRunner(false);

    // When
    runner.run(null);

    // Then
    verifyNoInteractions(userRepository, jdbcTemplate, writer);
  }

  @Test
  @DisplayName("게이트가 켜져도 k6 사정사 10명이 없으면 fail-fast로 아무것도 쓰지 않는다")
  void run_failsFastWhenAdjustersAreMissing() {
    // Given — 사정사 조회가 전부 빈 결과
    K6ScenarioSeedRunner runner = newRunner(true);
    when(userRepository.findByNickname(anyString())).thenReturn(Optional.empty());

    // When
    runner.run(null);

    // Then — 유저 생성·풀 카운트·시딩이 전혀 시작되지 않는다(절반만 만들어진 상태를 남기지 않는다)
    verifyNoInteractions(jdbcTemplate, writer);
  }

  /**
   * 게이트가 켜지는 사고를 프로파일이 한 겹 더 막아준다는 전제 자체를 고정한다. {@code @Profile} 값은
   * 문자열이라 오타(예: {@code &}를 {@code |}로)가 나도 컴파일·기동이 조용히 통과하고, 그 순간 이 러너는
   * prod에서도 빈으로 등록돼 프로퍼티 하나에만 의존하게 된다. 표현식을 애노테이션에서 직접 읽어
   * 프로파일 조합별로 평가한다(테스트가 문자열을 복사해 두면 같이 틀리므로 리터럴을 두지 않는다).
   */
  @Test
  @DisplayName("@Profile 표현식이 test·prod에서만 이 러너를 비활성화한다")
  void profileExpressionExcludesTestAndProd() {
    String expression = K6ScenarioSeedRunner.class.getAnnotation(Profile.class).value()[0];

    assertThat(isActiveWith(expression)).as("프로파일 미지정").isTrue();
    assertThat(isActiveWith(expression, "dev")).as("dev").isTrue();
    assertThat(isActiveWith(expression, "local")).as("local").isTrue();
    assertThat(isActiveWith(expression, "test")).as("test").isFalse();
    assertThat(isActiveWith(expression, "prod")).as("prod").isFalse();
    assertThat(isActiveWith(expression, "dev", "prod")).as("dev+prod 동시 지정").isFalse();
  }

  private boolean isActiveWith(String expression, String... activeProfiles) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.setActiveProfiles(activeProfiles);
    return environment.acceptsProfiles(Profiles.of(expression));
  }

  private K6ScenarioSeedRunner newRunner(boolean enabled) {
    return new K6ScenarioSeedRunner(enabled, 100, 8, 36, 2, 5, userRepository, jdbcTemplate, writer);
  }
}
