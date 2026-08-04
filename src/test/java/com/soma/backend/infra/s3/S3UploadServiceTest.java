package com.soma.backend.infra.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * S3UploadService(S3 인프라 어댑터) 단위 테스트. S3Client를 mock으로 대체해 실제 S3 호출 없이 putObject 요청
 * 조립(bucket·key·Content-Type·contentLength), SdkException·IOException → UPLOAD_FAILED 매핑, object URL
 * 위임을 검증한다. bucket은 @Value 주입 필드라 ReflectionTestUtils로 세팅한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3UploadService 단위 테스트")
class S3UploadServiceTest {

  private static final String BUCKET = "test-bucket";
  private static final String KEY = "avatars/uuid.png";
  private static final String CONTENT_TYPE = "image/png";
  private static final String OBJECT_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png";
  private static final String DOCUMENT_KEY = "report-documents/uuid.pdf";
  private static final String PRESIGNED_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png?X-Amz-Signature=abc";
  private static final String EXTERNAL_URL = "https://cdn.example.com/profile.png";
  private static final byte[] BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47};

  @InjectMocks
  private S3UploadService s3UploadService;

  @Mock
  private S3Client s3Client;

  @Mock
  private S3Presigner s3Presigner;

  @BeforeEach
  void setBucket() {
    ReflectionTestUtils.setField(s3UploadService, "bucket", BUCKET);
  }

  @Test
  @DisplayName("putObject는 bucket·key·Content-Type·contentLength를 실어 S3Client.putObject에 위임한다")
  void putObject_buildsRequestAndDelegates() {
    // Given
    given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .willReturn(PutObjectResponse.builder().build());
    MultipartFile file = new MockMultipartFile("file", "profile.png", CONTENT_TYPE, BYTES);

    // When
    s3UploadService.putObject(KEY, CONTENT_TYPE, file);

    // Then
    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    then(s3Client).should().putObject(captor.capture(), any(RequestBody.class));
    PutObjectRequest request = captor.getValue();
    assertThat(request.bucket()).isEqualTo(BUCKET);
    assertThat(request.key()).isEqualTo(KEY);
    assertThat(request.contentType()).isEqualTo(CONTENT_TYPE);
    assertThat(request.contentLength()).isEqualTo((long) BYTES.length);
  }

  @Test
  @DisplayName("putObject는 SDK가 SdkException을 던지면 UPLOAD_FAILED로 변환한다")
  void putObject_mapsSdkExceptionToUploadFailed() {
    // Given
    given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .willThrow(SdkClientException.create("put failed"));
    MultipartFile file = new MockMultipartFile("file", "profile.png", CONTENT_TYPE, BYTES);

    // When & Then
    assertThatThrownBy(() -> s3UploadService.putObject(KEY, CONTENT_TYPE, file))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_FAILED);
  }

  @Test
  @DisplayName("putObject는 파일 스트림을 열 수 없으면(IOException) UPLOAD_FAILED로 변환한다")
  void putObject_mapsIoExceptionToUploadFailed() throws Exception {
    // Given
    MultipartFile file = mock(MultipartFile.class);
    given(file.getSize()).willReturn((long) BYTES.length);
    given(file.getInputStream()).willThrow(new IOException("boom"));

    // When & Then
    assertThatThrownBy(() -> s3UploadService.putObject(KEY, CONTENT_TYPE, file))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_FAILED);
  }

  @Test
  @DisplayName("objectUrl은 S3Utilities.getUrl에 bucket·key를 위임하고 쿼리 없는 object URL을 반환한다")
  void objectUrl_delegatesToS3Utilities() throws Exception {
    // Given
    S3Utilities utilities = mock(S3Utilities.class);
    given(s3Client.utilities()).willReturn(utilities);
    given(utilities.getUrl(any(GetUrlRequest.class))).willReturn(URI.create(OBJECT_URL).toURL());

    // When
    String result = s3UploadService.objectUrl(KEY);

    // Then
    assertThat(result).isEqualTo(OBJECT_URL);
    ArgumentCaptor<GetUrlRequest> captor = ArgumentCaptor.forClass(GetUrlRequest.class);
    then(utilities).should().getUrl(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo(KEY);
  }

  @Test
  @DisplayName("presignedGetUrl은 bare key를 그대로 실어 단기 서명 GET URL을 발급한다")
  void presignedGetUrl_bareKey_signsWithKey() throws Exception {
    // Given
    givenPresignerReturns(PRESIGNED_URL);

    // When
    String result = s3UploadService.presignedGetUrl(DOCUMENT_KEY);

    // Then
    assertThat(result).isEqualTo(PRESIGNED_URL);
    assertThat(capturedPresignRequest().getObjectRequest().bucket()).isEqualTo(BUCKET);
    assertThat(capturedPresignRequest().getObjectRequest().key()).isEqualTo(DOCUMENT_KEY);
  }

  @Test
  @DisplayName("presignedGetUrl은 우리 버킷 object URL이면 path에서 key를 뽑아 서명한다")
  void presignedGetUrl_ourBucketUrl_extractsKey() throws Exception {
    // Given
    givenPresignerReturns(PRESIGNED_URL);

    // When
    String result = s3UploadService.presignedGetUrl(OBJECT_URL);

    // Then
    assertThat(result).isEqualTo(PRESIGNED_URL);
    assertThat(capturedPresignRequest().getObjectRequest().key()).isEqualTo(KEY);
  }

  @Test
  @DisplayName("presignedGetUrl은 외부 호스트 URL(소셜 프로필 등)이면 서명하지 않고 원본을 반환한다")
  void presignedGetUrl_externalUrl_returnsOriginal() {
    // When
    String result = s3UploadService.presignedGetUrl(EXTERNAL_URL);

    // Then
    assertThat(result).isEqualTo(EXTERNAL_URL);
    then(s3Presigner).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("presignedGetUrl은 null·blank는 서명 없이 그대로 통과시킨다")
  void presignedGetUrl_nullOrBlank_passesThrough() {
    // When & Then
    assertThat(s3UploadService.presignedGetUrl(null)).isNull();
    assertThat(s3UploadService.presignedGetUrl("  ")).isEqualTo("  ");
    then(s3Presigner).shouldHaveNoInteractions();
  }

  private void givenPresignerReturns(String url) throws Exception {
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    given(presigned.url()).willReturn(URI.create(url).toURL());
    given(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).willReturn(presigned);
  }

  private GetObjectPresignRequest capturedPresignRequest() {
    ArgumentCaptor<GetObjectPresignRequest> captor =
        ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    then(s3Presigner).should().presignGetObject(captor.capture());
    return captor.getValue();
  }
}
