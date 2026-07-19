package com.soma.backend.domain.chat.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatMessageListResponse;
import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.dto.SendMessageRequest;
import com.soma.backend.domain.chat.dto.UploadAttachmentResponse;
import com.soma.backend.domain.chat.service.ChatAttachmentService;
import com.soma.backend.domain.chat.service.ChatMessageCommandService;
import com.soma.backend.domain.chat.service.ChatMessageQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 채팅 메시지 이력·전송·첨부 업로드 API(설계서 §4 ②③⑦). 모두 참여자만 접근 가능하며 세부 가드는 서비스가 담당한다.
 */
@RestController
@RequiredArgsConstructor
public class ChatMessageController {

  private static final int DEFAULT_SIZE = 30;

  private final ChatMessageQueryService chatMessageQueryService;
  private final ChatMessageCommandService chatMessageCommandService;
  private final ChatAttachmentService chatAttachmentService;

  @GetMapping("/chats/{chatRoomId}/messages")
  public ResponseEntity<ApiResponse<ChatMessageListResponse>> getMessages(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID chatRoomId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
    UUID me = requireUserId(principal);
    return ResponseEntity.ok(
        ApiResponse.ok(chatMessageQueryService.getMessages(me, chatRoomId, cursor, size)));
  }

  @PostMapping("/chats/{chatRoomId}/messages")
  public ResponseEntity<ApiResponse<ChatMessageResponse>> send(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID chatRoomId,
      @RequestBody SendMessageRequest request) {
    UUID me = requireUserId(principal);
    ChatMessageResponse data = chatMessageCommandService.send(me, chatRoomId, request);
    return ResponseEntity.status(201).body(ApiResponse.created("메시지를 전송했습니다.", data));
  }

  @PostMapping("/chats/{chatRoomId}/attachments")
  public ResponseEntity<ApiResponse<UploadAttachmentResponse>> uploadAttachment(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID chatRoomId,
      @RequestParam("file") MultipartFile file) {
    UUID me = requireUserId(principal);
    UploadAttachmentResponse data = chatAttachmentService.upload(me, chatRoomId, file);
    return ResponseEntity.status(201).body(ApiResponse.created("첨부를 업로드했습니다.", data));
  }

  private UUID requireUserId(CustomUserDetails principal) {
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
    }
    return principal.getUserId();
  }
}
