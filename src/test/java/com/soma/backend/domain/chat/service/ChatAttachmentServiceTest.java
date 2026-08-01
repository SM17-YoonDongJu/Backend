package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.UploadAttachmentResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.ChatAttachmentUploader;

/**
 * 첨부 업로드(설계서 §4 ⑦) 단위 테스트. S3 실제 putObject는 {@link ChatAttachmentUploader}를 mock으로 대체하고
 * 화이트리스트·용량·인가 검증 로직만 확인한다(TODO: 실제 S3 연동은 인프라 필요해 통합 스킵).
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

  private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ChatAttachmentUploader chatAttachmentUploader;

  @InjectMocks
  private ChatAttachmentService service;

  private UUID userId;
  private UUID adjusterId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  private ChatRoom activeRoom() {
    return ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
  }

  @Test
  @DisplayName("허용 타입(image/png)이고 용량 이내면 업로드에 성공한다")
  void upload_allowedTypeAndSize_succeeds() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    given(chatAttachmentUploader.upload(eq(roomId), any())).willReturn("chat/" + roomId + "/uuid_photo.png");
    byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes);

    UploadAttachmentResponse response = service.upload(userId, roomId, file);

    assertThat(response.attachmentKey()).isEqualTo("chat/" + roomId + "/uuid_photo.png");
    assertThat(response.name()).isEqualTo("photo.png");
    assertThat(response.contentType()).isEqualTo("image/png");
    assertThat(response.size()).isEqualTo(8);
  }

  @Test
  @DisplayName("선언은 image/jpeg인데 실제 바이트가 JPEG 시그니처가 아니면 CHAT_ATTACHMENT_TYPE_NOT_ALLOWED(400)")
  void upload_spoofedMime_throws400() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    MultipartFile file = new MockMultipartFile("file", "evil.jpg", "image/jpeg", "not a real jpeg".getBytes());

    assertThatThrownBy(() -> service.upload(userId, roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED));
    verify(chatAttachmentUploader, never()).upload(any(), any());
  }

  @Test
  @DisplayName("허용되지 않는 형식(text/plain)이면 CHAT_ATTACHMENT_TYPE_NOT_ALLOWED(400)")
  void upload_disallowedType_throws400() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    MultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{1});

    assertThatThrownBy(() -> service.upload(userId, roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED));
    verify(chatAttachmentUploader, never()).upload(any(), any());
  }

  @Test
  @DisplayName("용량이 10MB를 초과하면 CHAT_ATTACHMENT_TOO_LARGE(400)")
  void upload_tooLarge_throws400() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    MultipartFile file = mock(MultipartFile.class);
    given(file.isEmpty()).willReturn(false);
    given(file.getSize()).willReturn(MAX_SIZE_BYTES + 1);

    assertThatThrownBy(() -> service.upload(userId, roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ATTACHMENT_TOO_LARGE));
    verify(chatAttachmentUploader, never()).upload(any(), any());
  }

  @Test
  @DisplayName("빈 파일이면 CHAT_ATTACHMENT_EMPTY(400)")
  void upload_emptyFile_throws400() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    MultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

    assertThatThrownBy(() -> service.upload(userId, roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ATTACHMENT_EMPTY));
  }

  @Test
  @DisplayName("참여자가 아니면 CHAT_NOT_A_MEMBER(403) — 검증 이전에 인가부터 확인한다")
  void upload_notAMember_throws403() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});

    assertThatThrownBy(() -> service.upload(UUID.randomUUID(), roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
    verify(chatAttachmentUploader, never()).upload(any(), any());
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404)")
  void upload_roomNotFound_throws404() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());
    MultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});

    assertThatThrownBy(() -> service.upload(userId, roomId, file))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }
}
