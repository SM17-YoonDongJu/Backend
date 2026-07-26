package com.soma.backend.domain.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.upload.dto.CreatePresignedUploadRequest;
import com.soma.backend.domain.upload.dto.PresignedUploadResponse;
import com.soma.backend.domain.upload.entity.UploadPurpose;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.PresignedUploadService;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService 단위 테스트")
class UploadServiceTest {

  private static final Duration EXPECTED_TTL = Duration.ofMinutes(5);
  private static final String UPLOAD_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/k?sig=abc";
  private static final String S3_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/k";

  @InjectMocks
  private UploadService uploadService;

  @Mock
  private PresignedUploadService presignedUploadService;

  @Test
  @DisplayName("report_document + PDF면 presigner에 위임하고 upload_url·s3_url을 반환한다")
  void createUploadUrl_returnsPresignedResponse_whenReportDocumentPdf() {
    // Given
    given(presignedUploadService.presignPut(anyKey(), eq("application/pdf"), eq(EXPECTED_TTL)))
        .willReturn(UPLOAD_URL);
    given(presignedUploadService.objectUrl(anyKey())).willReturn(S3_URL);
    CreatePresignedUploadRequest request = request("application/pdf", UploadPurpose.REPORT_DOCUMENT);

    // When
    PresignedUploadResponse response = uploadService.createUploadUrl(request);

    // Then
    assertThat(response.uploadUrl()).isEqualTo(UPLOAD_URL);
    assertThat(response.s3Url()).isEqualTo(S3_URL);
    String key = capturedPresignKey("application/pdf");
    assertThat(prefixOf(key)).isEqualTo("report-documents");
    assertThat(extensionOf(key)).isEqualTo("pdf");
    assertThatCode(() -> UUID.fromString(uuidPartOf(key))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("avatar + image/png면 avatars prefix와 .png 확장자로 key를 만든다")
  void createUploadUrl_buildsAvatarKeyWithPngExtension() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/png", UploadPurpose.AVATAR);

    // When
    uploadService.createUploadUrl(request);

    // Then
    String key = capturedPresignKey("image/png");
    assertThat(prefixOf(key)).isEqualTo("avatars");
    assertThat(extensionOf(key)).isEqualTo("png");
  }

  @Test
  @DisplayName("image/jpeg는 jpeg가 아니라 jpg 확장자로 매핑된다")
  void createUploadUrl_mapsJpegToJpgExtension() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/jpeg", UploadPurpose.AVATAR);

    // When
    uploadService.createUploadUrl(request);

    // Then
    assertThat(extensionOf(capturedPresignKey("image/jpeg"))).isEqualTo("jpg");
  }

  @Test
  @DisplayName("image/webp는 webp 확장자로 매핑된다")
  void createUploadUrl_mapsWebpExtension() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/webp", UploadPurpose.AVATAR);

    // When
    uploadService.createUploadUrl(request);

    // Then
    assertThat(extensionOf(capturedPresignKey("image/webp"))).isEqualTo("webp");
  }

  @Test
  @DisplayName("license 용도는 licenses prefix로 key를 만든다")
  void createUploadUrl_usesLicensePrefix() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/png", UploadPurpose.LICENSE);

    // When
    uploadService.createUploadUrl(request);

    // Then
    assertThat(prefixOf(capturedPresignKey("image/png"))).isEqualTo("licenses");
  }

  @Test
  @DisplayName("registration 용도는 registrations prefix로 key를 만든다")
  void createUploadUrl_usesRegistrationPrefix() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/png", UploadPurpose.REGISTRATION);

    // When
    uploadService.createUploadUrl(request);

    // Then
    assertThat(prefixOf(capturedPresignKey("image/png"))).isEqualTo("registrations");
  }

  @Test
  @DisplayName("presignPut과 objectUrl에는 동일한 key가 전달된다(s3_url↔key 왕복 정합)")
  void createUploadUrl_delegatesSameKeyToPresignAndObjectUrl() {
    // Given
    stubPresigner();
    CreatePresignedUploadRequest request = request("image/png", UploadPurpose.AVATAR);

    // When
    uploadService.createUploadUrl(request);

    // Then
    ArgumentCaptor<String> presignKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
    then(presignedUploadService).should().presignPut(presignKey.capture(), eq("image/png"), eq(EXPECTED_TTL));
    then(presignedUploadService).should().objectUrl(objectKey.capture());
    assertThat(presignKey.getValue()).isEqualTo(objectKey.getValue());
  }

  @Test
  @DisplayName("avatar + application/pdf면 예외를 던지고 presigner를 호출하지 않는다")
  void createUploadUrl_throws_whenAvatarWithPdf() {
    // Given
    CreatePresignedUploadRequest request = request("application/pdf", UploadPurpose.AVATAR);

    // When & Then
    assertThatThrownBy(() -> uploadService.createUploadUrl(request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    then(presignedUploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("report_document + image/gif면 예외를 던진다")
  void createUploadUrl_throws_whenReportDocumentWithGif() {
    // Given
    CreatePresignedUploadRequest request = request("image/gif", UploadPurpose.REPORT_DOCUMENT);

    // When & Then
    assertThatThrownBy(() -> uploadService.createUploadUrl(request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    then(presignedUploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("content_type에 charset 파라미터가 붙으면 엄격 매칭으로 거부한다")
  void createUploadUrl_throws_whenContentTypeHasCharsetParam() {
    // Given
    CreatePresignedUploadRequest request = request("image/png; charset=utf-8", UploadPurpose.AVATAR);

    // When & Then
    assertThatThrownBy(() -> uploadService.createUploadUrl(request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
  }

  @Test
  @DisplayName("content_type이 대문자면 엄격 매칭으로 거부한다")
  void createUploadUrl_throws_whenContentTypeUppercase() {
    // Given
    CreatePresignedUploadRequest request = request("IMAGE/PNG", UploadPurpose.AVATAR);

    // When & Then
    assertThatThrownBy(() -> uploadService.createUploadUrl(request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
  }

  private void stubPresigner() {
    given(presignedUploadService.presignPut(anyKey(), anyContentType(), eq(EXPECTED_TTL)))
        .willReturn(UPLOAD_URL);
    given(presignedUploadService.objectUrl(anyKey())).willReturn(S3_URL);
  }

  private String capturedPresignKey(String contentType) {
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    then(presignedUploadService).should().presignPut(keyCaptor.capture(), eq(contentType), eq(EXPECTED_TTL));
    return keyCaptor.getValue();
  }

  private static CreatePresignedUploadRequest request(String contentType, UploadPurpose purpose) {
    return new CreatePresignedUploadRequest("original-file", contentType, purpose);
  }

  private static String anyKey() {
    return org.mockito.ArgumentMatchers.anyString();
  }

  private static String anyContentType() {
    return org.mockito.ArgumentMatchers.anyString();
  }

  private static String prefixOf(String key) {
    return key.substring(0, key.indexOf('/'));
  }

  private static String uuidPartOf(String key) {
    String fileName = key.substring(key.indexOf('/') + 1);
    return fileName.substring(0, fileName.lastIndexOf('.'));
  }

  private static String extensionOf(String key) {
    return key.substring(key.lastIndexOf('.') + 1);
  }
}
