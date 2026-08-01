package com.soma.backend.domain.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.soma.backend.domain.upload.dto.UploadResponse;
import com.soma.backend.domain.upload.entity.UploadPurpose;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * UploadService(서버 경유 프록시 업로드) 단위 테스트. S3 경계(S3UploadService)를 mock으로 대체하고 purpose별
 * 화이트리스트·용량 검증, 매직바이트 위장 탐지, key 조립(prefix·확장자), s3_url 반환을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadService 단위 테스트")
class UploadServiceTest {

  private static final String S3_URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/k";
  private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
  private static final byte[] PDF_BYTES = {0x25, 0x50, 0x44, 0x46, 0x2D};

  @InjectMocks
  private UploadService uploadService;

  @Mock
  private S3UploadService s3UploadService;

  @Test
  @DisplayName("report_document + PDF면 S3에 저장하고 s3_url을 반환한다(report-documents/UUID.pdf)")
  void upload_reportDocumentPdf_storesAndReturnsS3Url() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "diagnosis.pdf", "application/pdf", PDF_BYTES);

    // When
    UploadResponse response = uploadService.upload(file, UploadPurpose.REPORT_DOCUMENT);

    // Then
    assertThat(response.s3Url()).isEqualTo(S3_URL);
    String key = capturedKey("application/pdf");
    assertThat(prefixOf(key)).isEqualTo("report-documents");
    assertThat(extensionOf(key)).isEqualTo("pdf");
    assertThatCode(() -> UUID.fromString(uuidPartOf(key))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("avatar + image/png면 avatars prefix와 .png 확장자로 저장한다")
  void upload_avatarPng_buildsAvatarKey() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "profile.png", "image/png", PNG_BYTES);

    // When
    uploadService.upload(file, UploadPurpose.AVATAR);

    // Then
    String key = capturedKey("image/png");
    assertThat(prefixOf(key)).isEqualTo("avatars");
    assertThat(extensionOf(key)).isEqualTo("png");
  }

  @Test
  @DisplayName("image/jpeg는 jpeg가 아니라 jpg 확장자로 매핑된다")
  void upload_jpeg_mapsToJpgExtension() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "profile.jpg", "image/jpeg", JPEG_BYTES);

    // When
    uploadService.upload(file, UploadPurpose.AVATAR);

    // Then
    assertThat(extensionOf(capturedKey("image/jpeg"))).isEqualTo("jpg");
  }

  @Test
  @DisplayName("license 용도는 licenses prefix로 저장한다")
  void upload_license_usesLicensePrefix() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "license.png", "image/png", PNG_BYTES);

    // When
    uploadService.upload(file, UploadPurpose.LICENSE);

    // Then
    assertThat(prefixOf(capturedKey("image/png"))).isEqualTo("licenses");
  }

  @Test
  @DisplayName("registration 용도는 registrations prefix로 저장한다")
  void upload_registration_usesRegistrationPrefix() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "reg.png", "image/png", PNG_BYTES);

    // When
    uploadService.upload(file, UploadPurpose.REGISTRATION);

    // Then
    assertThat(prefixOf(capturedKey("image/png"))).isEqualTo("registrations");
  }

  @Test
  @DisplayName("putObject와 objectUrl에는 동일한 key가 전달된다(저장↔URL 정합)")
  void upload_delegatesSameKeyToPutObjectAndObjectUrl() {
    // Given
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
    MultipartFile file = new MockMultipartFile("file", "p.png", "image/png", PNG_BYTES);

    // When
    uploadService.upload(file, UploadPurpose.AVATAR);

    // Then
    ArgumentCaptor<String> putKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> urlKey = ArgumentCaptor.forClass(String.class);
    then(s3UploadService).should().putObject(putKey.capture(), eq("image/png"), any(MultipartFile.class));
    then(s3UploadService).should().objectUrl(urlKey.capture());
    assertThat(putKey.getValue()).isEqualTo(urlKey.getValue());
  }

  @Test
  @DisplayName("avatar + application/pdf면 예외를 던지고 S3에 저장하지 않는다")
  void upload_throws_whenAvatarWithPdf() {
    // Given
    MultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", PDF_BYTES);

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.AVATAR))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    then(s3UploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("report_document + image/gif면 예외를 던진다")
  void upload_throws_whenReportDocumentWithGif() {
    // Given
    MultipartFile file = new MockMultipartFile("file", "anim.gif", "image/gif", new byte[]{0x47, 0x49, 0x46});

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.REPORT_DOCUMENT))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    then(s3UploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("선언은 image/png인데 실제 바이트가 PDF 시그니처면 위장으로 간주해 거부한다")
  void upload_throws_whenDeclaredPngButBytesArePdf() {
    // Given
    MultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", PDF_BYTES);

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.AVATAR))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    then(s3UploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("빈 파일이면 UPLOAD_FILE_EMPTY를 던진다")
  void upload_throws_whenEmpty() {
    // Given
    MultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.AVATAR))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_FILE_EMPTY);
    then(s3UploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("avatar 용량이 5MB를 초과하면 UPLOAD_FILE_TOO_LARGE를 던진다(내용 검증 이전)")
  void upload_throws_whenAvatarTooLarge() {
    // Given
    MultipartFile file = mock(MultipartFile.class);
    given(file.isEmpty()).willReturn(false);
    given(file.getSize()).willReturn(5L * 1024 * 1024 + 1);

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.AVATAR))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    then(s3UploadService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("content_type에 charset 파라미터가 붙으면 엄격 매칭으로 거부한다")
  void upload_throws_whenContentTypeHasCharsetParam() {
    // Given
    MultipartFile file = new MockMultipartFile("file", "p.png", "image/png; charset=utf-8", PNG_BYTES);

    // When & Then
    assertThatThrownBy(() -> uploadService.upload(file, UploadPurpose.AVATAR))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
  }

  private String capturedKey(String contentType) {
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    then(s3UploadService).should().putObject(keyCaptor.capture(), eq(contentType), any(MultipartFile.class));
    return keyCaptor.getValue();
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
