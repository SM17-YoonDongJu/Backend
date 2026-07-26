package com.soma.backend.infra.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * PresignedUploadService(S3 인프라 어댑터) 단위 테스트. S3Presigner·S3Client를 mock으로 대체해 실제 S3
 * 호출 없이 presigned PUT 요청 조립(bucket·key·Content-Type 바인딩·TTL), SdkException → EXTERNAL_API_ERROR
 * 매핑, object URL 위임을 검증한다. bucket은 @Value 주입 필드라 ReflectionTestUtils로 세팅한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresignedUploadService 단위 테스트")
class PresignedUploadServiceTest {

  private static final String BUCKET = "test-bucket";
  private static final String KEY = "avatars/uuid.png";
  private static final String CONTENT_TYPE = "image/png";
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final String PRESIGNED_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png?X-Amz-Signature=abc";
  private static final String OBJECT_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png";

  @InjectMocks
  private PresignedUploadService presignedUploadService;

  @Mock
  private S3Presigner s3Presigner;

  @Mock
  private S3Client s3Client;

  @BeforeEach
  void setBucket() {
    ReflectionTestUtils.setField(presignedUploadService, "bucket", BUCKET);
  }

  @Test
  @DisplayName("presignPut은 bucket·key·Content-Type·TTL을 서명 요청에 실어 presigned URL 문자열을 반환한다")
  void presignPut_buildsRequestAndReturnsUrl() throws Exception {
    // Given
    PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
    given(presigned.url()).willReturn(URI.create(PRESIGNED_URL).toURL());
    given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).willReturn(presigned);

    // When
    String result = presignedUploadService.presignPut(KEY, CONTENT_TYPE, TTL);

    // Then
    assertThat(result).isEqualTo(PRESIGNED_URL);
    ArgumentCaptor<PutObjectPresignRequest> captor =
        ArgumentCaptor.forClass(PutObjectPresignRequest.class);
    then(s3Presigner).should().presignPutObject(captor.capture());
    PutObjectPresignRequest presignRequest = captor.getValue();
    assertThat(presignRequest.signatureDuration()).isEqualTo(TTL);
    assertThat(presignRequest.putObjectRequest().bucket()).isEqualTo(BUCKET);
    assertThat(presignRequest.putObjectRequest().key()).isEqualTo(KEY);
    assertThat(presignRequest.putObjectRequest().contentType()).isEqualTo(CONTENT_TYPE);
  }

  @Test
  @DisplayName("presignPut은 SDK가 SdkException을 던지면 EXTERNAL_API_ERROR로 변환한다")
  void presignPut_mapsSdkExceptionToExternalApiError() {
    // Given
    given(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
        .willThrow(SdkClientException.create("presign failed"));

    // When & Then
    assertThatThrownBy(() -> presignedUploadService.presignPut(KEY, CONTENT_TYPE, TTL))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("objectUrl은 S3Utilities.getUrl에 bucket·key를 위임하고 쿼리 없는 object URL을 반환한다")
  void objectUrl_delegatesToS3Utilities() throws Exception {
    // Given
    S3Utilities utilities = mock(S3Utilities.class);
    given(s3Client.utilities()).willReturn(utilities);
    given(utilities.getUrl(any(GetUrlRequest.class))).willReturn(URI.create(OBJECT_URL).toURL());

    // When
    String result = presignedUploadService.objectUrl(KEY);

    // Then
    assertThat(result).isEqualTo(OBJECT_URL);
    ArgumentCaptor<GetUrlRequest> captor = ArgumentCaptor.forClass(GetUrlRequest.class);
    then(utilities).should().getUrl(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(captor.getValue().key()).isEqualTo(KEY);
  }
}
