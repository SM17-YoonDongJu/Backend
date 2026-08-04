package com.soma.backend.domain.chat.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatReportRequest;
import com.soma.backend.domain.chat.dto.ChatReportResponse;
import com.soma.backend.domain.chat.service.ChatReportCommandService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/** 채팅방 신고 접수 API. 방 참여자 본인만 접수할 수 있으며 세부 가드는 서비스가 담당한다. */
@RestController
@RequiredArgsConstructor
public class ChatReportController {

  private final ChatReportCommandService chatReportCommandService;

  @PostMapping("/chats/{chatRoomId}/report")
  public ResponseEntity<ApiResponse<ChatReportResponse>> report(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID chatRoomId,
      @RequestBody ChatReportRequest request) {
    UUID me = requireUserId(principal);
    ChatReportResponse data = chatReportCommandService.report(me, chatRoomId, request);
    return ResponseEntity.status(201).body(ApiResponse.created("신고가 접수되었습니다.", data));
  }

  private UUID requireUserId(CustomUserDetails principal) {
    if (principal == null) {
      throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
    }
    return principal.getUserId();
  }
}
