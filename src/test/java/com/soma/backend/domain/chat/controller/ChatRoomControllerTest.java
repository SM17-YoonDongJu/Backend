package com.soma.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.dto.ReadResponse;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.service.ChatConsultationCommandService;
import com.soma.backend.domain.chat.service.ChatReadService;
import com.soma.backend.domain.chat.service.ChatRoomQueryService;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 채팅방 목록·상담 결정·읽음 API(설계서 §4 ①④⑤⑥) 컨트롤러 테스트. 서비스는 Mockito mock으로 대체하고
 * 컨트롤러의 인가 가드(principal null→401)와 상태코드 매핑만 검증하는 standalone MockMvc다
 * (전체 SecurityFilterChain 없이 @AuthenticationPrincipal만 스프링 시큐리티 리졸버로 태운다).
 */
class ChatRoomControllerTest {

  private MockMvc mockMvc;
  private ChatRoomQueryService chatRoomQueryService;
  private ChatConsultationCommandService chatConsultationCommandService;
  private ChatReadService chatReadService;

  private final UUID roomId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    chatRoomQueryService = Mockito.mock(ChatRoomQueryService.class);
    chatConsultationCommandService = Mockito.mock(ChatConsultationCommandService.class);
    chatReadService = Mockito.mock(ChatReadService.class);
    ChatRoomController controller =
        new ChatRoomController(chatRoomQueryService, chatConsultationCommandService, chatReadService);
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
  @DisplayName("GET /chats — 비로그인이면 401 LOGIN_REQUIRED")
  void listMyRooms_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/chats"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("GET /chats — 로그인하면 200")
  void listMyRooms_authenticated_returns200() throws Exception {
    authenticateAs(UUID.randomUUID(), "USER");
    given(chatRoomQueryService.listMyRooms(any())).willReturn(new ChatRoomListResponse(List.of()));

    mockMvc.perform(get("/chats")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("PATCH /chats/{id}/accept — 비로그인이면 401 LOGIN_REQUIRED")
  void accept_unauthenticated_returns401() throws Exception {
    mockMvc.perform(patch("/chats/{id}/accept", roomId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("PATCH /chats/{id}/accept — 정상 수락이면 200")
  void accept_success_returns200() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.accept(me, roomId)).willReturn(new ConsultationDecisionResponse(
        roomId, ChatRoomStatus.ACTIVE, ReviewStatus.ACCEPTED, UUID.randomUUID(), ReportStatus.CLOSED));

    mockMvc.perform(patch("/chats/{id}/accept", roomId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").exists());
  }

  @Test
  @DisplayName("PATCH /chats/{id}/accept — 소유자가 아니면 403 CHAT_NOT_ROOM_OWNER")
  void accept_notOwner_returns403() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.accept(me, roomId))
        .willThrow(new BusinessException(ErrorCode.CHAT_NOT_ROOM_OWNER));

    mockMvc.perform(patch("/chats/{id}/accept", roomId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_ROOM_OWNER"));
  }

  @Test
  @DisplayName("PATCH /chats/{id}/accept — 검색 방(제안 없음)이면 409 CHAT_CONSULTATION_UNAVAILABLE")
  void accept_searchRoom_returns409() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.accept(me, roomId))
        .willThrow(new BusinessException(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE));

    mockMvc.perform(patch("/chats/{id}/accept", roomId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHAT_CONSULTATION_UNAVAILABLE"));
  }

  @Test
  @DisplayName("PATCH /chats/{id}/accept — 방이 없으면 404 CHAT_ROOM_NOT_FOUND")
  void accept_roomNotFound_returns404() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.accept(me, roomId))
        .willThrow(new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

    mockMvc.perform(patch("/chats/{id}/accept", roomId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
  }

  @Test
  @DisplayName("PATCH /chats/{id}/reject — 비로그인이면 401 LOGIN_REQUIRED")
  void reject_unauthenticated_returns401() throws Exception {
    mockMvc.perform(patch("/chats/{id}/reject", roomId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("PATCH /chats/{id}/reject — 정상 거절이면 200")
  void reject_success_returns200() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.reject(me, roomId)).willReturn(new ConsultationDecisionResponse(
        roomId, ChatRoomStatus.CLOSED, ReviewStatus.REJECTED, UUID.randomUUID(), ReportStatus.AWAITING_ADOPTION));

    mockMvc.perform(patch("/chats/{id}/reject", roomId)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("PATCH /chats/{id}/reject — 소유자가 아니면 403 CHAT_NOT_ROOM_OWNER")
  void reject_notOwner_returns403() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatConsultationCommandService.reject(me, roomId))
        .willThrow(new BusinessException(ErrorCode.CHAT_NOT_ROOM_OWNER));

    mockMvc.perform(patch("/chats/{id}/reject", roomId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_ROOM_OWNER"));
  }

  @Test
  @DisplayName("POST /chats/{id}/read — 비로그인이면 401 LOGIN_REQUIRED")
  void read_unauthenticated_returns401() throws Exception {
    mockMvc.perform(post("/chats/{id}/read", roomId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("POST /chats/{id}/read — 참여자면 200")
  void read_asMember_returns200() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatReadService.read(me, roomId)).willReturn(new ReadResponse(roomId, LocalDateTime.now()));

    mockMvc.perform(post("/chats/{id}/read", roomId)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("POST /chats/{id}/read — 참여자가 아니면 403 CHAT_NOT_A_MEMBER")
  void read_notAMember_returns403() throws Exception {
    UUID me = UUID.randomUUID();
    authenticateAs(me, "USER");
    given(chatReadService.read(me, roomId)).willThrow(new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER));

    mockMvc.perform(post("/chats/{id}/read", roomId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));
  }
}
