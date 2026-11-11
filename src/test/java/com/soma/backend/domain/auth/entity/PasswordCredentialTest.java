package com.soma.backend.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 로그인 아이디 정규화 규칙(Aggregate 소유) 단위 테스트. 가입(중복검사·저장)과 로그인(조회)이 같은
 * 규칙을 써야 하므로 규칙이 바뀌면 두 유스케이스가 동시에 어긋난다 — 그 지점을 엔티티 레벨에서 고정한다.
 */
@DisplayName("PasswordCredential 단위 테스트")
class PasswordCredentialTest {

  private static final String HASH = "$2a$10$storedhashvalue";

  @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
  @CsvSource({
    "HongGil, honggil",
    "HONGGIL, honggil",
    "honggil, honggil"
  })
  @DisplayName("아이디는 앞뒤 공백을 제거하고 소문자로 정규화된다")
  void normalizeLoginId_stripsAndLowercases(String input, String expected) {
    assertThat(PasswordCredential.normalizeLoginId(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("공백 제거는 스페이스뿐 아니라 탭·개행도 포함한다(strip)")
  void normalizeLoginId_stripsAllWhitespace() {
    assertThat(PasswordCredential.normalizeLoginId("\t HongGil \n")).isEqualTo("honggil");
  }

  @Test
  @DisplayName("기본 로케일이 터키어여도 I는 점 없는 ı가 아니라 i로 낮춘다(Locale.ROOT 고정)")
  void normalizeLoginId_isLocaleIndependent() {
    // Given
    Locale original = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr"));

    // When & Then
    try {
      assertThat(PasswordCredential.normalizeLoginId("HONGGIL")).isEqualTo("honggil");
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  @DisplayName("create는 호출자가 정규화를 빠뜨려도 아이디를 한 번 더 정규화해 보관한다")
  void create_normalizesLoginIdAgain() {
    // Given
    UUID userId = UUID.randomUUID();

    // When
    PasswordCredential credential = PasswordCredential.create(userId, "  HongGil  ", HASH);

    // Then
    assertThat(credential.getLoginId()).isEqualTo("honggil");
    assertThat(credential.getUserId()).isEqualTo(userId);
    assertThat(credential.getPasswordHash()).isEqualTo(HASH);
  }
}
