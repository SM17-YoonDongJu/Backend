package com.soma.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.soma.backend.domain.chat.dto.ChatMessageListResponse;
import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.dto.UploadAttachmentResponse;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.service.ChatAttachmentService;
import com.soma.backend.domain.chat.service.ChatMessageCommandService;
import com.soma.backend.domain.chat.service.ChatMessageQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 채팅 메시지 이력·전송·첨부 업로드 API(설계서 §4 ②③⑦) 컨트롤러 테스트. 서비스는 mock으로 대체한 standalone
 * MockMvc — 인가 가드(principal null→401, 비참여자→403)와 상태코드 매핑을 검증한다.
 */
class ChatMessageControllerTest {

  private MockMvc mockMvc;
  private ChatMessageQueryService chatMessageQueryService;
  private ChatMessageCommandService chatMessageCommandService;
  private ChatAttachmentService chatAttachmentService;

  private final UUID roomId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    chatMessageQueryService = Mockito.mock(ChatMessageQueryService.class);
    chatMessageCommandService = Mockito.mock(ChatMessageCommandService.class);
    chatAttachmentService = Mockito.mock(ChatAttachmentService.class);
    ChatMessageController controller =
        new ChatMessageController(chatMessageQueryService, chatMessageCommandService, chatAttachmentService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateAs(UUID userId, String role) {
    CustomUserDetails principal = new CustomUserDetails(userId, role);
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  @DisplayName("GET /chats/{id}/messages — 비로그인이면 401 LOGIN_REQUIRED")
  void getMessages_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/chats/{id}/messages", roomId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("GET /chats/{id}/messages — 참여자면 200")
  void getMessages_asMember_returns200() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageQueryService.getMessages(eq(me), eq(roomId), any(), anyInt()))
        .willReturn(new ChatMessageListResponse(List.of(), null, false));

    mockMvc.perform(get("/chats/{id}/messages", roomId)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET /chats/{id}/messages — 비참여자면 403 CHAT_NOT_A_MEMBER")
  void getMessages_notAMember_returns403() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageQueryService.getMessages(eq(me), eq(roomId), any(), anyInt()))
        .willThrow(new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER));

    mockMvc.perform(get("/chats/{id}/messages", roomId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));
  }

  @Test
  @DisplayName("GET /chats/{id}/messages — 방이 없으면 404 CHAT_ROOM_NOT_FOUND")
  void getMessages_roomNotFound_returns404() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageQueryService.getMessages(eq(me), eq(roomId), any(), anyInt()))
        .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

    mockMvc.perform(get("/chats/{id}/messages", roomId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
  }

  @Test
  @DisplayName("POST /chats/{id}/messages — 비로그인이면 401 LOGIN_REQUIRED")
  void send_unauthenticated_returns401() throws Exception {
    mockMvc.perform(post("/chats/{id}/messages", roomId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"안녕하세요\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("POST /chats/{id}/messages — 정상 전송이면 201")
  void send_success_returns201() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageCommandService.send(eq(me), eq(roomId), any())).willReturn(new ChatMessageResponse(
        UUID.randomUUID(), roomId, me, ChatMessageType.TEXT, "안녕하세요", List.of(), true, LocalDateTime.now()));

    mockMvc.perform(post("/chats/{id}/messages", roomId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"안녕하세요\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data").exists());
  }

  @Test
  @DisplayName("POST /chats/{id}/messages — content·attachment 둘 다 없으면 400 CHAT_MESSAGE_EMPTY")
  void send_bothEmpty_returns400() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageCommandService.send(eq(me), eq(roomId), any()))
        .willThrow(new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY));

    mockMvc.perform(post("/chats/{id}/messages", roomId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CHAT_MESSAGE_EMPTY"));
  }

  @Test
  @DisplayName("POST /chats/{id}/messages — 종료된 방이면 409 CHAT_ROOM_CLOSED")
  void send_closedRoom_returns409() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatMessageCommandService.send(eq(me), eq(roomId), any()))
        .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_CLOSED));

    mockMvc.perform(post("/chats/{id}/messages", roomId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"안녕하세요\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHAT_ROOM_CLOSED"));
  }

  @Test
  @DisplayName("POST /chats/{id}/attachments — 비로그인이면 401 LOGIN_REQUIRED")
  void uploadAttachment_unauthenticated_returns401() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});

    mockMvc.perform(multipart("/chats/{id}/attachments", roomId).file(file))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("POST /chats/{id}/attachments — 정상 업로드면 201")
  void uploadAttachment_success_returns201() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
    given(chatAttachmentService.upload(eq(me), eq(roomId), any()))
        .willReturn(new UploadAttachmentResponse("chat/" + roomId + "/uuid_photo.png", "photo.png", "image/png", 3L));

    mockMvc.perform(multipart("/chats/{id}/attachments", roomId).file(file))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data").exists());
  }

  @Test
  @DisplayName("POST /chats/{id}/attachments — 허용되지 않는 형식이면 400 CHAT_ATTACHMENT_TYPE_NOT_ALLOWED")
  void uploadAttachment_disallowedType_returns400() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain", new byte[]{1});
    given(chatAttachmentService.upload(eq(me), eq(roomId), any()))
        .willThrow(new BusinessException(ErrorCode.CHAT_ATTACHMENT_TYPE_NOT_ALLOWED));

    mockMvc.perform(multipart("/chats/{id}/attachments", roomId).file(file))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CHAT_ATTACHMENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("POST /chats/{id}/attachments — 참여자가 아니면 403 CHAT_NOT_A_MEMBER")
  void uploadAttachment_notAMember_returns403() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});
    given(chatAttachmentService.upload(eq(me), eq(roomId), any()))
        .willThrow(new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER));

    mockMvc.perform(multipart("/chats/{id}/attachments", roomId).file(file))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));
  }
}
