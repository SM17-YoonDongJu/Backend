package com.soma.backend.infra.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 문서·이미지의 서버 경유(프록시) 업로드를 담당하는 S3 인프라 어댑터. 컨트롤러가 수신한 MultipartFile
 * 바이트를 서버에서 직접 S3에 저장(putObject)하고, 저장된 object의 최종 URL을 조립한다. 기존 S3Client 빈과
 * aws.s3.bucket 프로퍼티를 재사용한다(presigned PUT URL 발급 방식을 대체). 저장 객체는 공개 ACL 없이 private로
 * 두므로, 조회 응답 경계에서 {@link #presignedGetUrl(String)}로 단기 서명 URL을 발급해 열람시킨다.
 */
@Component
@RequiredArgsConstructor
public class S3UploadService {

  /** presigned GET URL 만료(짧게 유지 — 민감 문서 노출 표면 축소). */
  private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  @Value("${aws.s3.bucket}")
  private String bucket;

  /** MultipartFile 바이트를 지정 key로 S3에 저장한다(content-length 지정 스트리밍 업로드). */
  public void putObject(String key, String contentType, MultipartFile file) {
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .contentType(contentType)
        .contentLength(file.getSize())
        .build();
    try (InputStream inputStream = file.getInputStream()) {
      s3Client.putObject(request, RequestBody.fromInputStream(inputStream, file.getSize()));
    } catch (IOException | SdkException ex) {
      throw new BusinessException(ErrorCode.UPLOAD_FAILED);
    }
  }

  /** 저장된 object의 최종 URL(쿼리스트링 없음)을 조립한다. */
  public String objectUrl(String key) {
    return s3Client.utilities()
        .getUrl(GetUrlRequest.builder().bucket(bucket).key(key).build())
        .toString();
  }

  /**
   * 저장된 값(s3_url 또는 object key)을 단기 presigned GET URL로 변환한다. 업로드 객체는 private라 서명 없이는
   * 열람이 안 되므로, 조회 응답 경계에서 이 메서드로 치환한다(채팅 첨부와 동일 정책). null·blank는 그대로
   * 통과시키고, 우리 버킷 객체가 아닌 외부 URL(예: 소셜 로그인 프로필 이미지)은 서명하지 않고 원본을 반환한다.
   */
  public @Nullable String presignedGetUrl(@Nullable String storedUrlOrKey) {
    if (storedUrlOrKey == null || storedUrlOrKey.isBlank()) {
      return storedUrlOrKey;
    }
    String key = ownObjectKey(storedUrlOrKey);
    if (key == null) {
      return storedUrlOrKey;
    }
    GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(PRESIGN_TTL)
        .getObjectRequest(getRequest)
        .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  /**
   * 저장 값에서 우리 버킷 object key를 뽑는다. 스킴 없는 bare key는 그대로, 우리 버킷 virtual-hosted S3 URL이면
   * path에서 key를 추출한다. 외부 호스트 URL이거나 파싱 불가면 null(우리 객체가 아니므로 서명하지 않는다).
   */
  private @Nullable String ownObjectKey(String stored) {
    URI uri;
    try {
      uri = URI.create(stored);
    } catch (IllegalArgumentException ex) {
      return null;
    }
    String host = uri.getHost();
    if (host == null) {
      return stripLeadingSlash(stored);
    }
    if (!host.startsWith(bucket + ".s3")) {
      return null;
    }
    String path = uri.getPath();
    return path == null || path.isBlank() ? null : stripLeadingSlash(path);
  }

  private String stripLeadingSlash(String value) {
    return value.startsWith("/") ? value.substring(1) : value;
  }
}
