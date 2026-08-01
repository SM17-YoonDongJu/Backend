package com.soma.backend.infra.s3;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 문서·이미지의 서버 경유(프록시) 업로드를 담당하는 S3 인프라 어댑터. 컨트롤러가 수신한 MultipartFile
 * 바이트를 서버에서 직접 S3에 저장(putObject)하고, 저장된 object의 최종 URL을 조립한다. 기존 S3Client 빈과
 * aws.s3.bucket 프로퍼티를 재사용한다(presigned PUT URL 발급 방식을 대체).
 */
@Component
@RequiredArgsConstructor
public class S3UploadService {

  private final S3Client s3Client;

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
}
