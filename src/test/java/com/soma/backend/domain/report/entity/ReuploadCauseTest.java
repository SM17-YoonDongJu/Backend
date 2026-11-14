package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@link ReuploadCause} 단위 테스트(design.md §9-1 U2) — §2-4 판정 표와 §2-5 문구(리더 승인본)를 그대로 고정한다.
 *
 * <p>문구 상수를 여기 따로 적어 두는 이유: 운영 코드의 상수를 그대로 비교하면 문구가 바뀌어도 테스트가 따라 바뀐다.
 * 조회 응답·푸시·인앱 알림함이 이 문구를 함께 쓰므로 바꿀 때는 이 테스트도 의식적으로 고쳐야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ReuploadCause 단위 테스트")
class ReuploadCauseTest {

  static final String DOMAIN_MESSAGE =
      "업로드하신 문서에서 보험 청구 서류를 찾지 못했어요. 보험증권·진단서 같은 청구 서류를 올려주세요.";
  static final String MISSING_GENERIC_MESSAGE = "보험증권과 진단서가 모두 필요해요. 두 서류를 함께 다시 올려주세요.";
  static final String POLICY_MESSAGE = "업로드하신 문서에서 보험증권을 확인하지 못했어요. 보험증권을 다시 올려주세요.";
  static final String DIAGNOSIS_MESSAGE = "업로드하신 문서에서 진단서를 확인하지 못했어요. 진단서를 다시 올려주세요.";
  static final String BOTH_MESSAGE =
      "업로드하신 문서에서 보험증권과 진단서를 확인하지 못했어요. 두 서류를 다시 올려주세요.";

  private static List<String> missingDocTypeWarnings(CapturedOutput output) {
    return output.getAll().lines()
        .filter(line -> line.contains("WARN"))
        .filter(line -> line.contains("알 수 없는 missing_doc_types"))
        .toList();
  }

  static Stream<Arguments> recognizedMissingDocTypes() {
    return Stream.of(
        Arguments.of(List.of("policy"), EnumSet.of(RequiredDocumentType.POLICY), POLICY_MESSAGE),
        Arguments.of(List.of("diagnosis"), EnumSet.of(RequiredDocumentType.DIAGNOSIS), DIAGNOSIS_MESSAGE),
        Arguments.of(List.of("policy", "diagnosis"), EnumSet.allOf(RequiredDocumentType.class), BOTH_MESSAGE),
        Arguments.of(List.of("diagnosis", "policy"), EnumSet.allOf(RequiredDocumentType.class), BOTH_MESSAGE),
        Arguments.of(List.of("policy", "policy", "diagnosis"), EnumSet.allOf(RequiredDocumentType.class),
            BOTH_MESSAGE));
  }

  @ParameterizedTest(name = "missing_doc_types={0}")
  @MethodSource("recognizedMissingDocTypes")
  @DisplayName("missing_required — 확인하지 못한 서류가 특정되면 서류별 문구다(순서·중복 무관, 경고 없음)")
  void missingRequiredNamesRecognizedDocuments(
      List<String> missingDocTypes, Set<RequiredDocumentType> expected, String message, CapturedOutput output) {
    ReuploadCause cause = ReuploadCause.from("missing_required", missingDocTypes);

    assertThat(cause).isNotNull();
    assertThat(cause.reason()).isEqualTo(ReuploadReason.MISSING_REQUIRED_DOCUMENTS);
    assertThat(cause.missingDocuments()).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(cause.userMessage()).isEqualTo(message);
    assertThat(missingDocTypeWarnings(output)).isEmpty();
  }

  static Stream<Arguments> unspecifiedMissingDocTypes() {
    return Stream.of(Arguments.of(List.of()), Arguments.of((Object) null));
  }

  @ParameterizedTest(name = "missing_doc_types={0}")
  @MethodSource("unspecifiedMissingDocTypes")
  @DisplayName("missing_required — 빈 배열·NULL은 서류를 특정하지 않는 일반 미확인 문구이고 경고가 없다")
  void missingRequiredWithoutDocumentsUsesGenericMessage(List<String> missingDocTypes, CapturedOutput output) {
    ReuploadCause cause = ReuploadCause.from("missing_required", missingDocTypes);

    assertThat(cause).isNotNull();
    assertThat(cause.reason()).isEqualTo(ReuploadReason.MISSING_REQUIRED_DOCUMENTS);
    assertThat(cause.missingDocuments()).isEmpty();
    assertThat(cause.userMessage()).isEqualTo(MISSING_GENERIC_MESSAGE);
    assertThat(missingDocTypeWarnings(output)).isEmpty();
  }

  static Stream<Arguments> unknownMissingDocTypes() {
    return Stream.of(
        Arguments.of(Arrays.asList((String) null)),
        Arguments.of(Arrays.asList("policy", null)),
        Arguments.of(List.of("policy", "medical_receipt")),
        Arguments.of(List.of("POLICY")));
  }

  @ParameterizedTest(name = "missing_doc_types={0}")
  @MethodSource("unknownMissingDocTypes")
  @DisplayName("missing_required — NULL 원소·모르는 유형이 하나라도 섞이면 일부만 짚지 않고 일반 문구 + 경고 1줄이다")
  void unknownMissingDocTypeFallsBackToGenericWithWarning(List<String> missingDocTypes, CapturedOutput output) {
    ReuploadCause cause = ReuploadCause.from("missing_required", missingDocTypes);

    assertThat(cause).isNotNull();
    assertThat(cause.reason()).isEqualTo(ReuploadReason.MISSING_REQUIRED_DOCUMENTS);
    assertThat(cause.missingDocuments()).isEmpty();
    assertThat(cause.userMessage()).isEqualTo(MISSING_GENERIC_MESSAGE);
    assertThat(missingDocTypeWarnings(output)).hasSize(1);
  }

  @Test
  @DisplayName("off_domain — missing_doc_types가 채워져 있어도 도메인 문구이고 서류 집합은 비며 경고가 없다")
  void offDomainIgnoresMissingDocTypes(CapturedOutput output) {
    ReuploadCause cause = ReuploadCause.from("off_domain", List.of("policy", "diagnosis", "medical_receipt"));

    assertThat(cause).isNotNull();
    assertThat(cause.reason()).isEqualTo(ReuploadReason.DOMAIN_MISMATCH);
    assertThat(cause.missingDocuments()).isEmpty();
    assertThat(cause.userMessage()).isEqualTo(DOMAIN_MESSAGE);
    assertThat(missingDocTypeWarnings(output)).isEmpty();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @NullSource
  @ValueSource(strings = {"unreadable", "brand_new"})
  @DisplayName("NULL·unreadable·모르는 사유는 원인을 만들지 않는다(null → 기존 일반 안내)")
  void generalGuidanceReasonsHaveNoCause(String reupload) {
    assertThat(ReuploadCause.from(reupload, List.of("policy"))).isNull();
  }

  @Test
  @DisplayName("서류 집합은 불변이다 — 입력을 바꿔도, 결과를 바꾸려 해도 판정이 변하지 않는다")
  void missingDocumentsAreImmutable() {
    List<String> raw = new ArrayList<>(List.of("policy"));
    ReuploadCause cause = ReuploadCause.from("missing_required", raw);
    raw.add("diagnosis");

    assertThat(cause).isNotNull();
    assertThat(cause.missingDocuments()).containsExactly(RequiredDocumentType.POLICY);
    assertThatThrownBy(() -> cause.missingDocuments().add(RequiredDocumentType.DIAGNOSIS))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("생성자 — 사유는 필수이고, 필수 서류 미확인 사유가 아니면 서류 집합을 비운다(null도 빈 집합)")
  void constructorEnforcesInvariants() {
    assertThatThrownBy(() -> new ReuploadCause(null, Set.of())).isInstanceOf(NullPointerException.class);
    assertThat(new ReuploadCause(ReuploadReason.DOMAIN_MISMATCH, Set.of(RequiredDocumentType.POLICY))
        .missingDocuments()).isEmpty();
    assertThat(new ReuploadCause(ReuploadReason.MISSING_REQUIRED_DOCUMENTS, null).missingDocuments())
        .isNotNull().isEmpty();
  }

  @Test
  @DisplayName("문구 컴플라이언스 — 사용자 과실 단정·보상·법률 표현이 없다(용어집 §12)")
  void messagesDoNotBlameUser() {
    List<String> messages = List.of(DOMAIN_MESSAGE, MISSING_GENERIC_MESSAGE, POLICY_MESSAGE, DIAGNOSIS_MESSAGE,
        BOTH_MESSAGE, ReportAnalysis.needsReupload().failureMessage());

    assertThat(messages).allSatisfy(message ->
        assertThat(message).doesNotContain("잘못", "빠뜨", "빠진", "누락", "보상", "보험금", "법적"));
  }
}
