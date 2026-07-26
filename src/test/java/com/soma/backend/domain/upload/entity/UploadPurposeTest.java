package com.soma.backend.domain.upload.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UploadPurpose 단위 테스트")
class UploadPurposeTest {

  @Test
  @DisplayName("from은 소문자 값마다 대응하는 상수를 반환한다")
  void from_returnsMatchingConstant_forEachLowercaseValue() {
    // When & Then
    assertThat(UploadPurpose.from("report_document")).isEqualTo(UploadPurpose.REPORT_DOCUMENT);
    assertThat(UploadPurpose.from("license")).isEqualTo(UploadPurpose.LICENSE);
    assertThat(UploadPurpose.from("registration")).isEqualTo(UploadPurpose.REGISTRATION);
    assertThat(UploadPurpose.from("avatar")).isEqualTo(UploadPurpose.AVATAR);
  }

  @Test
  @DisplayName("from은 알 수 없는 값이면 IllegalArgumentException을 던진다")
  void from_throwsIllegalArgumentException_whenValueUnknown() {
    // When & Then
    assertThatThrownBy(() -> UploadPurpose.from("passport"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("passport");
  }

  @Test
  @DisplayName("from은 대문자 값이면 IllegalArgumentException을 던진다(대소문자 무시 안 함)")
  void from_throwsIllegalArgumentException_whenValueUppercase() {
    // When & Then
    assertThatThrownBy(() -> UploadPurpose.from("AVATAR"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("from은 null 값이면 NPE가 아니라 IllegalArgumentException을 던진다")
  void from_throwsIllegalArgumentException_whenValueNull() {
    // When & Then
    assertThatThrownBy(() -> UploadPurpose.from(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("getValue는 상수마다 소문자 직렬화 값을 반환한다")
  void getValue_returnsLowercaseValue() {
    // When & Then
    assertThat(UploadPurpose.REPORT_DOCUMENT.getValue()).isEqualTo("report_document");
    assertThat(UploadPurpose.LICENSE.getValue()).isEqualTo("license");
    assertThat(UploadPurpose.REGISTRATION.getValue()).isEqualTo("registration");
    assertThat(UploadPurpose.AVATAR.getValue()).isEqualTo("avatar");
  }

  @Test
  @DisplayName("keyPrefix는 상수마다 지정된 S3 prefix를 반환한다")
  void keyPrefix_returnsExpectedPrefix() {
    // When & Then
    assertThat(UploadPurpose.REPORT_DOCUMENT.keyPrefix()).isEqualTo("report-documents");
    assertThat(UploadPurpose.LICENSE.keyPrefix()).isEqualTo("licenses");
    assertThat(UploadPurpose.REGISTRATION.keyPrefix()).isEqualTo("registrations");
    assertThat(UploadPurpose.AVATAR.keyPrefix()).isEqualTo("avatars");
  }

  @Test
  @DisplayName("avatar는 이미지 MIME만 허용하고 PDF·비허용 이미지는 거부한다")
  void allows_avatar_permitsOnlyImages() {
    // When & Then
    assertThat(UploadPurpose.AVATAR.allows("image/jpeg")).isTrue();
    assertThat(UploadPurpose.AVATAR.allows("image/png")).isTrue();
    assertThat(UploadPurpose.AVATAR.allows("image/webp")).isTrue();
    assertThat(UploadPurpose.AVATAR.allows("application/pdf")).isFalse();
    assertThat(UploadPurpose.AVATAR.allows("image/gif")).isFalse();
  }

  @Test
  @DisplayName("문서 용도는 이미지와 PDF를 허용하고 비허용 타입은 거부한다")
  void allows_documentPurposes_permitImagesAndPdf() {
    // When & Then
    assertThat(UploadPurpose.REPORT_DOCUMENT.allows("application/pdf")).isTrue();
    assertThat(UploadPurpose.REPORT_DOCUMENT.allows("image/png")).isTrue();
    assertThat(UploadPurpose.LICENSE.allows("application/pdf")).isTrue();
    assertThat(UploadPurpose.REGISTRATION.allows("image/jpeg")).isTrue();
    assertThat(UploadPurpose.REPORT_DOCUMENT.allows("image/gif")).isFalse();
    assertThat(UploadPurpose.LICENSE.allows("text/plain")).isFalse();
  }

  @Test
  @DisplayName("모든 상수의 허용 화이트리스트는 null이 아니다(Types 홀더 초기화 순서 회귀)")
  void allowedContentTypes_isNeverNull_forEveryConstant() {
    // When & Then — allowedContentTypes가 null이면 allows 호출에서 NPE가 난다
    for (UploadPurpose purpose : UploadPurpose.values()) {
      assertThat(purpose.allows("image/png"))
          .as("%s는 image/png를 허용해야 하며 화이트리스트가 초기화되어 있어야 한다", purpose)
          .isTrue();
    }
  }
}
