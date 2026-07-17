package com.soma.backend.domain.chat.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.UploadAttachmentResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.ChatAttachmentUploader;

/**
 * 채팅 첨부 업로드(설계서 §4 ⑦). 참여자 인가 + 화이트리스트(pdf/jpg/png)·용량 검증 후 private S3에 저장하고
 * object key를 반환한다. S3 I/O라 트랜잭션 밖에서 수행한다(DB 쓰기 없음).
 */
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

  private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
  private static final Set<String> ALLOWED_CONTENT_TYPES =
      Set.of("application/pdf", "image/jpeg", "image/png");
  private static final byte[] PDF_SIGNATURE = {0x25, 0x50, 0x44, 0x46};
  private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  private final ChatRoomRepository chatRoomRepository;
  private final ChatAttachmentUploader chatAttachmentUploader;

  public UploadAttachmentResponse upload(UUID me, UUID roomId, MultipartFile file) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    if (room.getStatus() == ChatRoomStatus.CLOSED) {
      throw new BusinessException(ErrorCode.CHAT_ROOM_CLOSED);
    }
    validate(file);
    String key = chatAttachmentUploader.upload(roomId, file);
    return new UploadAttachmentResponse(key, file.getOriginalFilename(), file.getContentType(), file.getSize());
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_EMPTY);
    }
    if (file.getSize() > MAX_SIZE_BYTES) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TOO_LARGE);
    }
    String declared = file.getContentType();
    if (declared == null || !ALLOWED_CONTENT_TYPES.contains(declared)) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED);
    }
    // 클라이언트 선언 Content-Type만 믿으면 위장 업로드가 가능하므로 실제 파일 시그니처(매직바이트)로 재검증한다.
    String detected = detectContentType(file);
    if (detected == null || !detected.equals(declared)) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED);
    }
  }

  /** 파일 앞부분 매직바이트로 실제 콘텐츠 타입을 판별한다. 허용 3종(pdf/jpeg/png)이 아니면 null. */
  private String detectContentType(MultipartFile file) {
    byte[] header = new byte[8];
    int read;
    try (InputStream inputStream = file.getInputStream()) {
      read = inputStream.readNBytes(header, 0, header.length);
    } catch (IOException ex) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED);
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
