package com.soma.backend.global.security.crypto.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.security.crypto.PiiCipher;
import com.soma.backend.global.security.crypto.RawPiiDataKeyProvider;

/**
 * PII 컬럼 컨버터 경계 케이스 테스트(design.md §12.2 C12~C18). Hibernate 없이 컨버터를 직접 호출해
 * null 보존·JSON 왕복·날짜 왕복·주입 실패 fail-fast를 검증한다.
 *
 * <p>C14(원소에 null이 섞인 리스트)는 {@code PiiStringListConverter}가 역직렬화에서 {@code List.of(arr)}를
 * 쓰면 NPE로 깨지는 실제 버그를 잡는 케이스라 반드시 포함한다(§6).
 */
@DisplayName("PII 컬럼 컨버터 테스트")
class PiiConverterTest {

  private static final String DEV_KEY = Base64.getEncoder()
      .encodeToString("dev-local-pii-encryption-key-32b".getBytes(StandardCharsets.UTF_8));

  private final PiiCipher cipher = new PiiCipher(new RawPiiDataKeyProvider(DEV_KEY));
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private final UserClaimAdditionalInformationConverter stringConverter =
      new UserClaimAdditionalInformationConverter(cipher);
  private final UserInsuranceCoveragesConverter listConverter =
      new UserInsuranceCoveragesConverter(cipher, jsonMapper);
  private final UserInsuranceEnrolledAtConverter dateConverter =
      new UserInsuranceEnrolledAtConverter(cipher);

  @Test
  @DisplayName("C12 문자열 컨버터는 null 속성 ↔ null 컬럼을 NPE 없이 왕복시킨다")
  void stringConverter_nullPassesThrough() {
    assertThat(stringConverter.convertToDatabaseColumn(null)).isNull();
    assertThat(stringConverter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  @DisplayName("C12 리스트·날짜 컨버터도 null 속성 ↔ null 컬럼을 왕복시킨다")
  void listAndDateConverter_nullPassesThrough() {
    assertThat(listConverter.convertToDatabaseColumn(null)).isNull();
    assertThat(listConverter.convertToEntityAttribute(null)).isNull();
    assertThat(dateConverter.convertToDatabaseColumn(null)).isNull();
    assertThat(dateConverter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  @DisplayName("C12 문자열 컨버터는 평문을 담지 않은 봉투로 왕복한다")
  void stringConverter_roundTripsWithoutStoringPlaintext() {
    String plaintext = "보험사 안내와 달라 추가 설명합니다";

    byte[] column = stringConverter.convertToDatabaseColumn(plaintext);

    assertThat(new String(column, StandardCharsets.UTF_8)).doesNotContain("보험사");
    assertThat(stringConverter.convertToEntityAttribute(column)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("C13 빈 리스트는 null이 아닌 봉투로 저장되고 빈 리스트로 돌아온다(null과 구분)")
  void listConverter_emptyList_isDistinguishedFromNull() {
    byte[] column = listConverter.convertToDatabaseColumn(List.of());

    assertThat(column).isNotNull();
    assertThat(listConverter.convertToEntityAttribute(column)).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("C14 원소에 null이 섞인 리스트도 NPE 없이 왕복한다(List.of(arr) 사용 금지)")
  void listConverter_listWithNullElement_roundTrips() {
    List<String> attribute = Arrays.asList("상해후유장해", null, "골절진단비");

    byte[] column = listConverter.convertToDatabaseColumn(attribute);

    assertThat(listConverter.convertToEntityAttribute(column))
        .containsExactly("상해후유장해", null, "골절진단비");
  }

  @Test
  @DisplayName("C15 큰따옴표·역슬래시·개행이 든 원소도 JSON 이스케이프로 정확히 왕복한다")
  void listConverter_jsonEscapedElements_roundTrip() {
    List<String> attribute = List.of("\"골절\"진단비", "역\\슬래시", "줄\n바꿈", "탭\t포함", "{\"json\":\"like\"}");

    byte[] column = listConverter.convertToDatabaseColumn(attribute);

    assertThat(listConverter.convertToEntityAttribute(column)).isEqualTo(attribute);
  }

  @Test
  @DisplayName("C16 리스트 원소 순서가 그대로 보존된다")
  void listConverter_preservesElementOrder() {
    List<String> attribute = List.of("첫째", "둘째", "셋째", "넷째", "다섯째");

    byte[] column = listConverter.convertToDatabaseColumn(attribute);

    assertThat(listConverter.convertToEntityAttribute(column))
        .containsExactly("첫째", "둘째", "셋째", "넷째", "다섯째");
  }

  @Test
  @DisplayName("C16 같은 리스트를 두 번 저장해도 매번 다른 봉투가 되고 둘 다 같은 값으로 복호화된다")
  void listConverter_sameValueProducesDifferentEnvelopes() {
    List<String> attribute = List.of("실손의료비", "수술비");

    byte[] first = listConverter.convertToDatabaseColumn(attribute);
    byte[] second = listConverter.convertToDatabaseColumn(attribute);

    assertThat(first).isNotEqualTo(second);
    assertThat(listConverter.convertToEntityAttribute(first)).isEqualTo(attribute);
    assertThat(listConverter.convertToEntityAttribute(second)).isEqualTo(attribute);
  }

  @Test
  @DisplayName("C17 윤년(2024-02-29)·경계(9999-12-31)·최소(0001-01-01) 날짜가 정확히 왕복한다")
  void dateConverter_boundaryDates_roundTrip() {
    List<LocalDate> dates =
        List.of(LocalDate.of(2024, 2, 29), LocalDate.of(9999, 12, 31), LocalDate.of(1, 1, 1));

    for (LocalDate date : dates) {
      byte[] column = dateConverter.convertToDatabaseColumn(date);
      assertThat(dateConverter.convertToEntityAttribute(column)).isEqualTo(date);
    }
  }

  @Test
  @DisplayName("C17 날짜 봉투에는 ISO 문자열 평문이 남지 않는다")
  void dateConverter_doesNotStorePlaintext() {
    byte[] column = dateConverter.convertToDatabaseColumn(LocalDate.of(2024, 3, 15));

    assertThat(new String(column, StandardCharsets.UTF_8)).doesNotContain("2024-03-15");
  }

  @Test
  @DisplayName("C18 컨버터가 Spring 빈으로 생성되지 않아 PiiCipher가 null이면 NPE가 아니라 IllegalStateException")
  void converters_withoutInjectedCipher_failFast() {
    assertThatThrownBy(() -> new UserClaimAdditionalInformationConverter(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Spring 빈");
    assertThatThrownBy(() -> new UserInsuranceEnrolledAtConverter(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Spring 빈");
    assertThatThrownBy(() -> new UserInsuranceCoveragesConverter(null, jsonMapper))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Spring 빈");
    assertThatThrownBy(() -> new UserInsuranceCoveragesConverter(cipher, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Spring 빈");
  }

  @Test
  @DisplayName("컬럼별 컨버터는 AAD가 달라 서로의 봉투를 열지 못한다(컬럼 간 이식 차단)")
  void converters_areBoundToTheirOwnColumn() {
    UserInsuranceInsurerNameConverter insurerConverter = new UserInsuranceInsurerNameConverter(cipher);
    UserInsuranceProductNameConverter productConverter = new UserInsuranceProductNameConverter(cipher);
    UserInsurancePolicyNoConverter policyConverter = new UserInsurancePolicyNoConverter(cipher);

    byte[] insurerColumn = insurerConverter.convertToDatabaseColumn("OO손해보험");

    assertThat(insurerConverter.convertToEntityAttribute(insurerColumn)).isEqualTo("OO손해보험");
    assertThatThrownBy(() -> productConverter.convertToEntityAttribute(insurerColumn))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> policyConverter.convertToEntityAttribute(insurerColumn))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> stringConverter.convertToEntityAttribute(insurerColumn))
        .isInstanceOf(RuntimeException.class);
  }
}
