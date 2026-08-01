package com.soma.backend.domain.upload.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.upload.dto.UploadResponse;
import com.soma.backend.domain.upload.entity.UploadPurpose;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * 문서·이미지의 서버 경유(프록시) 업로드 유스케이스. purpose별 화이트리스트로 content_type과 용량을 검증하고,
 * 클라이언트 선언 타입을 실제 파일 시그니처(매직바이트)로 재검증한 뒤 서버가 생성한 UUID key로 S3에 저장하고
 * 최종 object URL을 반환한다. DB에 접근하지 않으므로 트랜잭션 경계가 없다(S3 I/O만 수행).
 */
@Service
@RequiredArgsConstructor
public class UploadService {

  private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46};
  private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  private final S3UploadService s3UploadService;

  /**
   * 업로드 파일을 검증하고 S3에 저장한 뒤 최종 object URL을 발급한다.
   *
   * @throws BusinessException 빈 파일이면 UPLOAD_FILE_EMPTY, purpose 용량 초과면 UPLOAD_FILE_TOO_LARGE,
   *                           선언·실제 content_type이 화이트리스트에 없거나 위장이면
   *                           UPLOAD_CONTENT_TYPE_NOT_ALLOWED
   */
  public UploadResponse upload(MultipartFile file, UploadPurpose purpose) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.UPLOAD_FILE_EMPTY);
    }
    if (file.getSize() > purpose.maxSizeBytes()) {
      throw new BusinessException(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    }
    String declared = file.getContentType();
    if (declared == null || !purpose.allows(declared)) {
      throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    }
    // 클라이언트 선언 Content-Type만 믿으면 위장 업로드가 가능하므로 실제 파일 시그니처(매직바이트)로 재검증한다.
    String detected = detectContentType(file);
    if (detected == null || !detected.equals(declared) || !purpose.allows(detected)) {
      throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    }
    String key = purpose.keyPrefix() + "/" + UUID.randomUUID() + "." + extensionFor(detected);
    s3UploadService.putObject(key, detected, file);
    return new UploadResponse(s3UploadService.objectUrl(key));
  }

  private String extensionFor(String contentType) {
    return switch (contentType) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "application/pdf" -> "pdf";
      default -> throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    };
  }

  /** 파일 앞부분 매직바이트로 실제 콘텐츠 타입을 판별한다. 허용 3종(pdf/jpeg/png)이 아니면 null. */
  private String detectContentType(MultipartFile file) {
    byte[] header = new byte[8];
    int read;
    try (InputStream inputStream = file.getInputStream()) {
      read = inputStream.readNBytes(header, 0, header.length);
    } catch (IOException ex) {
      throw new BusinessException(ErrorCode.UPLOAD_CONTENT_TYPE_NOT_ALLOWED);
    }
    if (startsWith(header, read, PNG_SIGNATURE)) {
      return "image/png";
    }
    if (startsWith(header, read, JPEG_SIGNATURE)) {
      return "image/jpeg";
    }
    if (startsWith(header, read, PDF_SIGNATURE)) {
      return "application/pdf";
    }
    return null;
  }

  private boolean startsWith(byte[] header, int length, byte[] signature) {
    if (length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if (header[index] != signature[index]) {
        return false;
      }
    }
    return true;
  }
}
