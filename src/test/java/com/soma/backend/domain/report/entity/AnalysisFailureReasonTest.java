package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link AnalysisFailureReason} ACL·대표 사유 우선순위 단위 테스트(design.md §15 Q5·Q6).
 *
 * <p>이 enum이 지키는 두 불변식을 고정한다.
 * <ol>
 *   <li><b>계약 확장 내성</b> — 미지의 {@code failure_class}는 예외가 아니라 UNKNOWN 폴백이다.
 *       AI팀의 CHECK 목록 확장 배포가 Backend보다 먼저 나가도 사용자 500이 되면 안 된다.</li>
 *   <li><b>재업로드 오안내 금지</b> — 재업로드로 해결 가능한 UNREADABLE_FILE은 다른 사유가 하나도
 *       섞이지 않았을 때만 대표가 된다(§8 E5).</li>
 * </ol>
 */
@DisplayName("AnalysisFailureReason VO 단위 테스트")
class AnalysisFailureReasonTest {

  @ParameterizedTest(name = "failure_class={0} → {1}")
  @CsvSource({
      "masking_residual, MASKING_RESIDUAL",
      "unreadable_file, UNREADABLE_FILE",
      "ocr_error, OCR_ERROR",
      "schema_invalid, SCHEMA_INVALID",
      "unknown, UNKNOWN"
  })
  @DisplayName("계약 문자열 5값(DB CHECK 제약)이 도메인 사유로 번역된다")
  void translatesContractFailureClasses(String failureClass, AnalysisFailureReason expected) {
    assertThat(AnalysisFailureReason.from(failureClass)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Q6 — 미지의 failure_class는 예외 없이 UNKNOWN으로 폴백한다(AI 계약 확장 내성)")
  void unknownFailureClassFallsBackWithoutException() {
    // Given: AI팀이 CHECK 목록에 새로 추가했지만 Backend는 아직 모르는 값
    String brandNew = "brand_new_value";

    // When & Then
    assertThatCode(() -> AnalysisFailureReason.from(brandNew)).doesNotThrowAnyException();
    assertThat(AnalysisFailureReason.from(brandNew)).isEqualTo(AnalysisFailureReason.UNKNOWN);
  }

  @Test
  @DisplayName("failure_class가 null이어도 UNKNOWN으로 폴백한다")
  void nullFailureClassFallsBackToUnknown() {
    assertThat(AnalysisFailureReason.from(null)).isEqualTo(AnalysisFailureReason.UNKNOWN);
  }

  @Test
  @DisplayName("Q5(최중요) — masking_residual + unreadable_file 혼재 시 대표 사유는 MASKING_RESIDUAL이다")
  void representativePrefersMaskingResidualOverUnreadableFile() {
    // Given: 한 리포트에 문서 2건이 서로 다른 사유로 실패
    List<AnalysisFailureReason> mixed =
        List.of(AnalysisFailureReason.UNREADABLE_FILE, AnalysisFailureReason.MASKING_RESIDUAL);

    // When
    AnalysisFailureReason representative = AnalysisFailureReason.representative(mixed);

    // Then: 재업로드해도 동일 실패인 사유가 이기고, 재업로드 안내는 나가지 않는다
    assertThat(representative).isEqualTo(AnalysisFailureReason.MASKING_RESIDUAL);
    assertThat(representative.getReuploadGuidance()).isEqualTo(ReuploadGuidance.NOT_SUPPORTED);
  }

  @ParameterizedTest(name = "UNREADABLE_FILE + {0} → 대표는 {0}")
  @EnumSource(value = AnalysisFailureReason.class,
      names = {"MASKING_RESIDUAL", "SCHEMA_INVALID", "OCR_ERROR", "UNKNOWN"})
  @DisplayName("다른 사유가 하나라도 섞이면 UNREADABLE_FILE은 절대 대표가 되지 않는다")
  void unreadableFileNeverWinsWhenOtherReasonPresent(AnalysisFailureReason other) {
    AnalysisFailureReason representative =
        AnalysisFailureReason.representative(List.of(AnalysisFailureReason.UNREADABLE_FILE, other));

    assertThat(representative).isEqualTo(other);
    assertThat(representative.getReuploadGuidance()).isNotEqualTo(ReuploadGuidance.RECOMMENDED);
  }

  @Test
  @DisplayName("UNREADABLE_FILE만 있으면 대표가 되고 재업로드를 안내한다(유일한 사용자 액션 가능 사유)")
  void unreadableFileAloneRecommendsReupload() {
    AnalysisFailureReason representative =
        AnalysisFailureReason.representative(List.of(AnalysisFailureReason.UNREADABLE_FILE));

    assertThat(representative).isEqualTo(AnalysisFailureReason.UNREADABLE_FILE);
    assertThat(representative.getReuploadGuidance()).isEqualTo(ReuploadGuidance.RECOMMENDED);
  }

  @Test
  @DisplayName("빈 컬렉션·null은 방어값 UNKNOWN이다(예외 없음)")
  void representativeOfEmptyIsUnknown() {
    assertThat(AnalysisFailureReason.representative(List.of())).isEqualTo(AnalysisFailureReason.UNKNOWN);
    assertThat(AnalysisFailureReason.representative(null)).isEqualTo(AnalysisFailureReason.UNKNOWN);
  }

  @Test
  @DisplayName("재업로드를 권하는 사유는 UNREADABLE_FILE 하나뿐이다(오안내 표면 최소화)")
  void onlyUnreadableFileRecommendsReupload() {
    List<AnalysisFailureReason> recommended = Arrays.stream(AnalysisFailureReason.values())
        .filter(reason -> reason.getReuploadGuidance() == ReuploadGuidance.RECOMMENDED)
        .toList();

    assertThat(recommended).containsExactly(AnalysisFailureReason.UNREADABLE_FILE);
  }

  @ParameterizedTest
  @EnumSource(AnalysisFailureReason.class)
  @DisplayName("컴플라이언스 — 사용자 문구에 보상금액·의학적 판단·법률 자문 표현이 없다")
  void userMessagesContainNoAmountOrLegalOrMedicalClaims(AnalysisFailureReason reason) {
    String message = reason.getUserMessage();

    assertThat(message).isNotBlank();
    // 변호사법·보험업법·금소법 리스크 표현(보상 단정·법적 판단·의학적 소견)은 처리 상태 문구에 들어가면 안 된다.
    assertThat(message).doesNotContain("보상", "보험금", "원", "지급", "법적", "법률", "청구 가능", "받을 수 있",
        "진단", "질병", "치료", "장해");
  }

  @Test
  @DisplayName("컴플라이언스 — masking_residual 문구는 사용자 과실이 아니라 시스템 검토로 중립 서술한다")
  void maskingResidualMessageDoesNotBlameUser() {
    AnalysisFailureReason reason = AnalysisFailureReason.MASKING_RESIDUAL;

    // 재업로드·수정 요구가 없어야 한다(개인정보 마스킹 잔류는 시스템 한계다).
    assertThat(reason.getUserMessage()).isEqualTo("문서를 검토 중입니다");
    assertThat(reason.getUserMessage()).doesNotContain("다시", "업로드", "확인해주세요", "잘못");
    assertThat(reason.getReuploadGuidance()).isEqualTo(ReuploadGuidance.NOT_SUPPORTED);
  }

  @ParameterizedTest
  @EnumSource(AnalysisFailureReason.class)
  @DisplayName("모든 사유가 문구·재업로드 안내·계약 문자열을 빠짐없이 정의한다(새 값 추가 시 컴파일 강제)")
  void everyReasonDefinesContractAndGuidance(AnalysisFailureReason reason) {
    assertThat(reason.getFailureClass()).isNotBlank();
    assertThat(reason.getUserMessage()).isNotBlank();
    assertThat(reason.getReuploadGuidance()).isNotNull();
    // 계약 문자열 ↔ enum 왕복이 성립해야 ACL이 한 지점으로 유지된다.
    assertThat(AnalysisFailureReason.from(reason.getFailureClass())).isEqualTo(reason);
  }
}
