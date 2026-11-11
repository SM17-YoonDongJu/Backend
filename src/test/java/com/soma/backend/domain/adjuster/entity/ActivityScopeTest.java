package com.soma.backend.domain.adjuster.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ActivityScope가 전문분야 목록을 원본과 끊어서 들고 있는지 검증한다(CodeRabbit PR #323 지적). */
@DisplayName("ActivityScope 전문분야 목록 보호")
class ActivityScopeTest {

  @Test
  @DisplayName("생성 뒤 원본 리스트를 바꿔도 활동범위에는 반영되지 않는다")
  void callerMutationDoesNotLeak() {
    List<String> original = new ArrayList<>(List.of("신체"));

    ActivityScope scope = new ActivityScope(original, "서울 송파", Affiliation.INDEPENDENT);
    original.add("재물");

    assertThat(scope.specialties()).containsExactly("신체");
  }

  @Test
  @DisplayName("접근자로 꺼낸 목록은 수정할 수 없다")
  void accessorReturnsImmutableList() {
    ActivityScope scope = new ActivityScope(List.of("신체"), "서울 송파", Affiliation.INDEPENDENT);

    assertThatThrownBy(() -> scope.specialties().add("재물"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("전문분야가 null이면 그대로 null — 복사 때문에 NPE가 나지 않는다")
  void nullSpecialtiesStaysNull() {
    assertThatCode(() -> new ActivityScope(null, "서울 송파", Affiliation.INDEPENDENT))
        .doesNotThrowAnyException();
    assertThat(new ActivityScope(null, "서울 송파", Affiliation.INDEPENDENT).specialties()).isNull();
  }
}
