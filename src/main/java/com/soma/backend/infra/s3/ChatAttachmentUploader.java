package com.soma.backend.infra.s3;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅 첨부의 private S3 저장·조회를 담당한다. 객체는 공개 ACL 없이 저장(버킷 기본 private)하고,
 * 조회는 단기 presigned GET URL로만 노출한다(진단서·증권 등 민감 문서 보호).
 */
@Component
@RequiredArgsConstructor
public class ChatAttachmentUploader {

  /** presigned GET URL 만료(짧게 유지). */
  private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  @Value("${aws.s3.bucket}")
  private String bucket;

  /**
   * 첨부를 {@code chat/{roomId}/{uuid}_{name}} key로 private 업로드하고 저장된 object key를 반환한다.
   */
  public String upload(UUID roomId, MultipartFile file) {
    String key = buildKey(roomId, file.getOriginalFilename());
    try {
      PutObjectRequest request = PutObjectRequest.builder()
          .bucket(bucket)
          .key(key)
          .contentType(file.getContentType())
          .contentLength(file.getSize())
          .build();
      s3Client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
    } catch (IOException ex) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_UPLOAD_FAILED);
    }
    return key;
  }

  /** 저장된 object key에 대한 단기 presigned GET URL을 발급한다(네트워크 왕복 없는 서명). */
  public String presignedGetUrl(String key) {
    GetObjectRequest getRequest = GetObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .build();
    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(PRESIGN_TTL)
        .getObjectRequest(getRequest)
        .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  private String buildKey(UUID roomId, String originalName) {
    String safeName = (originalName == null || originalName.isBlank())
        ? "file"
        : originalName.replace("/", "_").replace("\\", "_");
    return "chat/" + roomId + "/" + UUID.randomUUID() + "_" + safeName;
  }
}
