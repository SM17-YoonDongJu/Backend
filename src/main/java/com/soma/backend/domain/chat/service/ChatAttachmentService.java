package com.soma.backend.domain.chat.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.UploadAttachmentResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
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

  private final ChatRoomRepository chatRoomRepository;
  private final ChatAttachmentUploader chatAttachmentUploader;

  public UploadAttachmentResponse upload(UUID me, UUID roomId, MultipartFile file) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
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
    String contentType = file.getContentType();
    if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED);
    }
  }
}
