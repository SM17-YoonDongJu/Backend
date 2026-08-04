package com.soma.backend.domain.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatReport;
import com.soma.backend.domain.chat.entity.ChatReportReason;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatReportRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 채팅방 신고 접수 API(POST /chats/{chatRoomId}/report) 통합 테스트. 실제 SecurityFilterChain·전역 예외
 * 핸들러·test_db를 태워 설계서 §11의 E1~E33(스키마 전용 E28·E31 제외)을 검증한다.
 *
 * <p>{@code @Transactional}로 요청 트랜잭션이 테스트 트랜잭션에 합류해 종료 시 롤백된다. 저장 결과 확인은
 * flush·clear 후 다시 읽어 1차 캐시가 아니라 DB 상태를 보게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChatReportControllerTest {

  private static final String DETAIL_500 = "가".repeat(500);

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ChatRoomRepository chatRoomRepository;

  @Autowired
  private ChatReportRepository chatReportRepository;

  @Autowired
  private EntityManager entityManager;

  private UUID userId;
  private UUID adjusterId;
  private ChatRoom room;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    room = savedRoom(ChatRoomStatus.ACTIVE);
  }

  private ChatRoom savedRoom(ChatRoomStatus status) {
    return chatRoomRepository.save(ChatRoomFixture.build(userId, adjusterId, null, null, status));
  }

  private RequestPostProcessor loginAs(UUID accountId, String role) {
    CustomUserDetails principal = new CustomUserDetails(accountId, role);
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private List<ChatReport> reloadReports() {
    entityManager.flush();
    entityManager.clear();
    return chatReportRepository.findAll();
  }

  @Test
  @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
  void report_unauthenticated_returns401() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"ABUSE\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));

    assertThat(reloadReports()).isEmpty();
  }

  @Test
  @DisplayName("참여자 고객이 신고하면 201이고 응답 data는 4개 키(chat_report_id·chat_room_id·reason·created_at)만 담는다")
  void report_asCustomer_returns201WithContractShape() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"ABUSE\",\"reason_detail\":\"상담 중 반복적인 욕설이 있었습니다.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("201"))
        .andExpect(jsonPath("$.message").value("신고가 접수되었습니다."))
        .andExpect(jsonPath("$.data").value(aMapWithSize(4)))
        .andExpect(jsonPath("$.data.chat_report_id").isNotEmpty())
        .andExpect(jsonPath("$.data.chat_room_id").value(room.getId().toString()))
        .andExpect(jsonPath("$.data.reason").value("ABUSE"))
        .andExpect(jsonPath("$.data.created_at").isNotEmpty())
        .andExpect(jsonPath("$.data.reporter_id").doesNotExist())
        .andExpect(jsonPath("$.data.reported_id").doesNotExist())
        .andExpect(jsonPath("$.data.reason_detail").doesNotExist());

    List<ChatReport> reports = reloadReports();
    assertThat(reports).hasSize(1);
    ChatReport saved = reports.get(0);
    assertThat(saved.getChatRoomId()).isEqualTo(room.getId());
    assertThat(saved.getReporterId()).isEqualTo(userId);
    assertThat(saved.getReportedId()).isEqualTo(adjusterId);
    assertThat(saved.getReason()).isEqualTo(ChatReportReason.ABUSE);
    assertThat(saved.getReasonDetail()).isEqualTo("상담 중 반복적인 욕설이 있었습니다.");
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("참여자 사정사가 신고하면 201이고 reported_id는 고객(user_id)으로 저장된다")
  void report_asAdjuster_savesCustomerAsReported() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(adjusterId, "CERTIFICATED_ADJUSTER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"FRAUD\"}"))
        .andExpect(status().isCreated());

    List<ChatReport> reports = reloadReports();
    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).getReporterId()).isEqualTo(adjusterId);
    assertThat(reports.get(0).getReportedId()).isEqualTo(userId);
  }

  @Test
  @DisplayName("종료(CLOSED)된 방에도 신고가 201로 접수된다")
  void report_closedRoom_returns201() throws Exception {
    ChatRoom closed = savedRoom(ChatRoomStatus.CLOSED);

    mockMvc.perform(post("/chats/{id}/report", closed.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isCreated());

    assertThat(reloadReports()).hasSize(1);
    assertThat(chatRoomRepository.findById(closed.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.CLOSED);
  }

  @Test
  @DisplayName("같은 사용자가 같은 방을 연속 3번 신고해도 매번 201이고 3행이 적재된다")
  void report_threeTimes_persistsThreeRows() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(post("/chats/{id}/report", room.getId())
              .with(loginAs(userId, "USER"))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"reason\":\"SPAM\"}"))
          .andExpect(status().isCreated());
    }

    List<ChatReport> reports = reloadReports();
    assertThat(reports).hasSize(3);
    assertThat(reports.stream().map(ChatReport::getId).distinct()).hasSize(3);
  }

  @Test
  @DisplayName("신고해도 채팅방의 status·last_message·읽음 커서·updated_at은 갱신되지 않는다")
  void report_doesNotTouchChatRoomRow() throws Exception {
    room.touchLastMessage("마지막 메시지", LocalDateTime.of(2026, 8, 1, 10, 0));
    room.markRead(userId, LocalDateTime.of(2026, 8, 1, 11, 0));
    room.markRead(adjusterId, LocalDateTime.of(2026, 8, 1, 12, 0));
    entityManager.flush();
    entityManager.clear();
    ChatRoom before = chatRoomRepository.findById(room.getId()).orElseThrow();
    ChatRoomStatus beforeStatus = before.getStatus();
    String beforeLastMessage = before.getLastMessage();
    LocalDateTime beforeLastMessageAt = before.getLastMessageAt();
    LocalDateTime beforeUserRead = before.getUserLastReadAt();
    LocalDateTime beforeAdjusterRead = before.getAdjusterLastReadAt();
    LocalDateTime beforeUpdatedAt = before.getUpdatedAt();

    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"ABUSE\",\"reason_detail\":\"욕설\"}"))
        .andExpect(status().isCreated());

    entityManager.flush();
    entityManager.clear();
    ChatRoom after = chatRoomRepository.findById(room.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(beforeStatus);
    assertThat(after.getLastMessage()).isEqualTo(beforeLastMessage);
    assertThat(after.getLastMessageAt()).isEqualTo(beforeLastMessageAt);
    assertThat(after.getUserLastReadAt()).isEqualTo(beforeUserRead);
    assertThat(after.getAdjusterLastReadAt()).isEqualTo(beforeAdjusterRead);
    assertThat(after.getUpdatedAt()).isEqualTo(beforeUpdatedAt);
  }

  @Test
  @DisplayName("참여자가 아닌 제3자가 신고하면 403 CHAT_NOT_A_MEMBER")
  void report_thirdParty_returns403() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(UUID.randomUUID(), "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));

    assertThat(reloadReports()).isEmpty();
  }

  @Test
  @DisplayName("ADMIN이라도 참여자가 아니면 403 CHAT_NOT_A_MEMBER — 관리자 예외는 없다")
  void report_adminNonMember_returns403() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(UUID.randomUUID(), "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));
  }

  @Test
  @DisplayName("존재하지 않는 방이면 403보다 먼저 404 CHAT_ROOM_NOT_FOUND로 응답한다")
  void report_missingRoom_returns404BeforeMembershipCheck() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", UUID.randomUUID())
            .with(loginAs(UUID.randomUUID(), "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
  }

  @ParameterizedTest(name = "body={0}")
  @ValueSource(strings = {"{}", "{\"reason\":null}", "{\"reason\":\"   \"}"})
  @DisplayName("reason이 없거나 공백이면 400 MISSING_REQUIRED_FIELD")
  void report_missingReason_returns400(String json) throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));

    assertThat(reloadReports()).isEmpty();
  }

  @ParameterizedTest(name = "reason={0}")
  @ValueSource(strings = {"HARASSMENT", "abuse", "Other"})
  @DisplayName("정의되지 않은 사유나 소문자 사유는 400 VALIDATION_ERROR")
  void report_unsupportedReason_returns400(String reason) throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(reloadReports()).isEmpty();
  }

  @ParameterizedTest(name = "body={0}")
  @ValueSource(strings = {
    "{\"reason\":\"OTHER\"}",
    "{\"reason\":\"OTHER\",\"reason_detail\":null}",
    "{\"reason\":\"OTHER\",\"reason_detail\":\"   \"}"
  })
  @DisplayName("reason=OTHER인데 상세가 비면 400 MISSING_REQUIRED_FIELD")
  void report_otherWithoutDetail_returns400(String json) throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));

    assertThat(reloadReports()).isEmpty();
  }

  @Test
  @DisplayName("reason=OTHER에 상세를 채우면 201이고 상세가 그대로 저장된다")
  void report_otherWithDetail_returns201() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"OTHER\",\"reason_detail\":\"직접입력\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.reason").value("OTHER"));

    assertThat(reloadReports().get(0).getReasonDetail()).isEqualTo("직접입력");
  }

  @ParameterizedTest(name = "body={0}")
  @ValueSource(strings = {
    "{\"reason\":\"SPAM\"}",
    "{\"reason\":\"SPAM\",\"reason_detail\":\"\"}",
    "{\"reason\":\"SPAM\",\"reason_detail\":\"   \"}"
  })
  @DisplayName("OTHER가 아닌 사유는 상세가 비어도 201이고 reason_detail은 NULL로 저장된다")
  void report_nonOtherWithBlankDetail_savesNull(String json) throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isCreated());

    assertThat(reloadReports().get(0).getReasonDetail()).isNull();
  }

  /**
   * 설계서 §11 E18·E19. 현재 {@code ChatReport.reasonDetail} 매핑에 길이가 없어 Hibernate가 만드는
   * 스키마(test 프로파일 ddl-auto=create-drop)에서는 varchar(255)가 되고, V31의 {@code text}와 어긋나
   * 500자 입력이 INSERT에서 깨진다(value too long for type character varying(255)). 매핑에
   * {@code columnDefinition = "text"}(또는 {@code length = 500})를 넣으면 통과한다.
   */
  @Test
  @DisplayName("reason_detail이 정확히 500자면 201, 501자면 400 VALIDATION_ERROR")
  void report_detailLengthBoundary() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\",\"reason_detail\":\"" + DETAIL_500 + "\"}"))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\",\"reason_detail\":\"" + DETAIL_500 + "가\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    List<ChatReport> reports = reloadReports();
    assertThat(reports).hasSize(1);
    assertThat(reports.get(0).getReasonDetail()).hasSize(500);
  }

  /** 설계서 §11 E20·E21. 위 E18과 같은 매핑 원인으로 현재 실패한다(varchar(255) vs text). */
  @Test
  @DisplayName("앞뒤 공백이 붙어 502자여도 trim 후 500자면 201이고 trim된 값이 저장된다")
  void report_paddedDetail_isTrimmedAndAccepted() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\",\"reason_detail\":\" " + DETAIL_500 + " \"}"))
        .andExpect(status().isCreated());

    assertThat(reloadReports().get(0).getReasonDetail()).isEqualTo(DETAIL_500);
  }

  @Test
  @DisplayName("chatRoomId가 UUID 형식이 아니면 400 INVALID_REQUEST(404 아님)")
  void report_malformedRoomId_returns400() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", "not-a-uuid")
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"SPAM\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("요청 바디가 비어 있으면 400 INVALID_REQUEST(MISSING_REQUIRED_FIELD 아님)")
  void report_emptyBody_returns400InvalidRequest() throws Exception {
    mockMvc.perform(post("/chats/{id}/report", room.getId())
            .with(loginAs(userId, "USER"))
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(reloadReports()).isEmpty();
  }
}
