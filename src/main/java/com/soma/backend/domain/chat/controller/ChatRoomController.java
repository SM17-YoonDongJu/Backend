package com.soma.backend.domain.chat.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.dto.ReadResponse;
import com.soma.backend.domain.chat.service.ChatConsultationCommandService;
import com.soma.backend.domain.chat.service.ChatReadService;
import com.soma.backend.domain.chat.service.ChatRoomQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 채팅방 목록·상담 결정·읽음 API(설계서 §4 ①④⑤⑥). 인가는 principal null→401,
 * 목록/읽음은 참여자, 수락/거절은 방 소유자(user)만 — 세부 가드는 서비스가 담당한다.
 */
@RestController
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatRoomQueryService chatRoomQueryService;
  private final ChatConsultationCommandService chatConsultationCommandService;
  private final ChatReadService chatReadService;

  @GetMapping("/chats")
  public ResponseEntity<ApiResponse<ChatRoomListResponse>> listMyRooms(
      @AuthenticationPrincipal CustomUserDetails principal) {
    UUID me = requireUserId(principal);
    return ResponseEntity.ok(ApiResponse.ok(chatRoomQueryService.listMyRooms(me)));
  }

  @PatchMapping("/chats/{chatRoomId}/accept")
  public ResponseEntity<ApiResponse<ConsultationDecisionResponse>> accept(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID chatRoomId) {
    UUID me = requireUserId(principal);
    return ResponseEntity.ok(ApiResponse.ok(chatConsultationCommandService.accept(me, chatRoomId)));
  }

  @PatchMapping("/chats/{chatRoomId}/reject")
  public ResponseEntity<ApiResponse<ConsultationDecisionResponse>> reject(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID chatRoomId) {
    UUID me = requireUserId(principal);
    return ResponseEntity.ok(ApiResponse.ok(chatConsultationCommandService.reject(me, chatRoomId)));
  }

  @PostMapping("/chats/{chatRoomId}/read")
  public ResponseEntity<ApiResponse<ReadResponse>> read(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID chatRoomId) {
    UUID me = requireUserId(principal);
    return ResponseEntity.ok(ApiResponse.ok(chatReadService.read(me, chatRoomId)));
  }

  private UUID requireUserId(CustomUserDetails principal) {
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
    }
    return principal.getUserId();
  }
}
