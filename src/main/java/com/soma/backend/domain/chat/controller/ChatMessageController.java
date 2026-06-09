package com.soma.backend.domain.chat.controller;

import com.soma.backend.domain.chat.dto.MessageHistoryResponse;
import com.soma.backend.domain.chat.service.ChatMessageService;
import com.soma.backend.global.security.CustomUserDetails;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    @GetMapping("/{roomId}/messages")
    public ResponseEntity<MessageHistoryResponse> getMessages(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID roomId,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(
                chatMessageService.getMessages(roomId, principal.getUserId(), limit, cursor, principal.getRole()));
    }
}
