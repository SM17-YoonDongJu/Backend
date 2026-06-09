package com.soma.backend.domain.chat.controller;

import com.soma.backend.domain.chat.dto.CreateRoomRequest;
import com.soma.backend.domain.chat.dto.JoinRoomResponse;
import com.soma.backend.domain.chat.dto.RoomListResponse;
import com.soma.backend.domain.chat.dto.RoomResponse;
import com.soma.backend.domain.chat.service.ChatRoomService;
import com.soma.backend.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody CreateRoomRequest request) {
        RoomResponse response = chatRoomService.createRoom(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID roomId) {
        return ResponseEntity.ok(chatRoomService.getRoom(roomId, principal.getUserId(), principal.getRole()));
    }

    @GetMapping
    public ResponseEntity<RoomListResponse> getRooms(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok(chatRoomService.getRooms(principal.getUserId(), limit, cursor));
    }

    @PostMapping("/{roomId}/members")
    public ResponseEntity<JoinRoomResponse> joinRoom(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID roomId) {
        return ResponseEntity.ok(chatRoomService.joinRoom(roomId, principal.getUserId()));
    }

    @DeleteMapping("/{roomId}/members/me")
    public ResponseEntity<Void> leaveRoom(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable UUID roomId) {
        chatRoomService.leaveRoom(roomId, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
