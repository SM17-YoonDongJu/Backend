package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@link ReuploadReason} ACL 단위 테스트(design.md §9-1 U1) — {@code ai.claim_readiness.reupload_reason} 번역.
 *
 * <p>로그는 레벨 패턴(평문·Spring 콘솔)에 의존하지 않게 "WARN" 토큰과 이 ACL의 경고 문구가 같은 줄에 있는지로 고른다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ReuploadReason ACL 단위 테스트")
class ReuploadReasonTest {

  private static List<String> unknownValueWarnings(CapturedOutput output) {
    return output.getAll().lines()
        .filter(line -> line.contains("WARN"))
        .filter(line -> line.contains("알 수 없는 reupload_reason"))
        .toList();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"off_domain, DOMAIN_MISMATCH", "missing_required, MISSING_REQUIRED_DOCUMENTS"})
  @DisplayName("계약 값 off_domain·missing_required는 사유별 상수로 번역되고 경고가 없다")
  void translatesKnownValues(String externalValue, ReuploadReason expected, CapturedOutput output) {
    assertThat(ReuploadReason.from(externalValue)).isEqualTo(expected);
    assertThat(unknownValueWarnings(output)).isEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @NullSource
  @ValueSource(strings = "unreadable")
  @DisplayName("NULL(재업로드 아님·013 이전)과 unreadable(기존 일반 안내)은 경고 없이 null이다")
  void generalGuidanceValuesAreNullWithoutWarning(String externalValue, CapturedOutput output) {
    assertThat(ReuploadReason.from(externalValue)).isNull();
    assertThat(unknownValueWarnings(output)).isEmpty();
  }

  @ParameterizedTest(name = "[{index}] ''{0}''")
  @ValueSource(strings = {"brand_new", "OFF_DOMAIN", ""})
  @DisplayName("모르는 값(대소문자 다른 값·빈 문자열 포함)은 예외 없이 null이고 경고를 1줄 남긴다")
  void unknownValuesFallBackWithSingleWarning(String externalValue, CapturedOutput output) {
    ReuploadReason[] translated = new ReuploadReason[1];

    assertThatCode(() -> translated[0] = ReuploadReason.from(externalValue)).doesNotThrowAnyException();

    assertThat(translated[0]).isNull();
    assertThat(unknownValueWarnings(output)).singleElement().asString()
        .contains("reupload_reason='" + externalValue + "'");
  }

  @Test
  @DisplayName("상수 이름이 곧 API 응답 코드다 — 집합이 바뀌면 프론트 계약이 바뀐다는 경보")
  void constantNamesAreTheApiContract() {
    assertThat(Arrays.stream(ReuploadReason.values()).map(Enum::name))
        .containsExactlyInAnyOrder("DOMAIN_MISMATCH", "MISSING_REQUIRED_DOCUMENTS");
  }
}
