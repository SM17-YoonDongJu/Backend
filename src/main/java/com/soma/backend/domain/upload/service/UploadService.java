package com.soma.backend.domain.upload.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.upload.dto.CreatePresignedUploadRequest;
import com.soma.backend.domain.upload.dto.PresignedUploadResponse;
import com.soma.backend.domain.upload.entity.UploadPurpose;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.PresignedUploadService;

/**
 * 문서·이미지 업로드용 presigned PUT URL 발급 유스케이스. purpose별 화이트리스트로 content_type을
 * 검증하고, 서버가 생성한 UUID key로 presigned URL과 최종 object URL을 조립한다. DB에 접근하지 않으므로
 * 트랜잭션 경계가 없다(무상태 URL 발급).
 */
@Service
@RequiredArgsConstructor
public class UploadService {

  /** presigned URL 만료(기존 ChatAttachmentUploader와 정합). */
  private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

  private final PresignedUploadService presignedUploadService;

  /**
   * 업로드 요청을 검증하고 presigned PUT URL과 최종 object URL을 발급한다.
   *
   * @throws BusinessException content_type이 purpose 화이트리스트에 없으면 UPLOAD_CONTENT_TYPE_NOT_ALLOWED
   */
  public PresignedUploadResponse createUploadUrl(CreatePresignedUploadRequest request) {
    UploadPurpose purpose = request.purpose();
    if (!purpose.allows(request.contentType())) {
      throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    }
    String key = buildKey(purpose, request.contentType());
    String uploadUrl = presignedUploadService.presignPut(key, request.contentType(), PRESIGN_TTL);
    String s3Url = presignedUploadService.objectUrl(key);
    return new PresignedUploadResponse(uploadUrl, s3Url);
  }

  private String buildKey(UploadPurpose purpose, String contentType) {
    return purpose.keyPrefix() + "/" + UUID.randomUUID() + "." + extensionFor(contentType);
  }

  private String extensionFor(String contentType) {
    return switch (contentType) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "application/pdf" -> "pdf";
      default -> throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    };
  }
}
