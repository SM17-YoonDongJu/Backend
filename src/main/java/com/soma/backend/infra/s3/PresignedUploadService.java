package com.soma.backend.infra.s3;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 업로드용 presigned PUT URL 발급·object URL 조립을 담당하는 S3 인프라 어댑터. 기존 S3Presigner·S3Client
 * 빈과 aws.s3.bucket 프로퍼티를 재사용한다. presigned PUT은 Content-Type을 서명에 바인딩하므로 FE는 PUT
 * 요청에 동일한 Content-Type 헤더를 실어야 한다(불일치 시 S3가 403 SignatureDoesNotMatch).
 */
@Component
@RequiredArgsConstructor
public class PresignedUploadService {

  private final S3Presigner s3Presigner;
  private final S3Client s3Client;

  @Value("${aws.s3.bucket}")
  private String bucket;

  /** 지정 key로 presigned PUT URL을 발급한다(Content-Type을 서명에 바인딩, 네트워크 왕복 없는 서명). */
  public String presignPut(String key, String contentType, Duration ttl) {
    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentType)
        .build();
    PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
        .signatureDuration(ttl)
        .putObjectRequest(putObjectRequest)
        .build();
    try {
      return s3Presigner.presignPutObject(presignRequest).url().toString();
    } catch (SdkException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /** 최종 object URL(쿼리스트링 없음). presignPut URL과 동일한 host를 SDK가 조립한다. */
  public String objectUrl(String key) {
    return s3Client.utilities()
        .getUrl(GetUrlRequest.builder().bucket(bucket).key(key).build())
        .toString();
  }
}
