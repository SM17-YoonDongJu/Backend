package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ChatReportRequest;
import com.soma.backend.domain.chat.dto.ChatReportResponse;
import com.soma.backend.domain.chat.entity.ChatReport;
import com.soma.backend.domain.chat.entity.ChatReportReason;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatReportRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅방 신고 접수(설계서 §5.6) 단위 테스트. 설계서 §11 경계 케이스(E1~E3·E5~E7·E9~E21·E26·E27·E32)를
 * 커버한다. 특히 회귀 위험이 큰 세 가지(CLOSED 방 허용·중복 신고 허용·방 상태 불변)를 명시적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatReportCommandServiceTest {

  private static final String DETAIL_500 = "가".repeat(500);

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private ChatReportRepository chatReportRepository;

  @InjectMocks
  private ChatReportCommandService service;

  @Captor
  private ArgumentCaptor<ChatReport> reportCaptor;

  private UUID userId;
  private UUID adjusterId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  private ChatRoom givenRoom(ChatRoomStatus status) {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, status);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    return room;
  }

  /** save가 실제 영속화한 것처럼 id·createdAt을 채워 돌려준다(@GeneratedValue·@CreatedDate 대역). */
  private void givenSavePersists() {
    given(chatReportRepository.save(any(ChatReport.class))).willAnswer(invocation -> {
      ChatReport report = invocation.getArgument(0);
      ReflectionTestUtils.setField(report, "id", UUID.randomUUID());
      ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.now());
      return report;
    });
  }

  private ChatReportRequest request(String reason, String reasonDetail) {
    return new ChatReportRequest(reason, reasonDetail);
  }

  @Test
  @DisplayName("고객이 신고하면 reported_id는 사정사가 되고 응답은 요청한 방·사유를 그대로 돌려준다")
  void report_asCustomer_savesAdjusterAsReported() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    ChatReportResponse response = service.report(userId, roomId, request("ABUSE", "반복적인 욕설"));

    then(chatReportRepository).should().save(reportCaptor.capture());
    ChatReport saved = reportCaptor.getValue();
    assertThat(saved.getChatRoomId()).isEqualTo(roomId);
    assertThat(saved.getReporterId()).isEqualTo(userId);
    assertThat(saved.getReportedId()).isEqualTo(adjusterId);
    assertThat(saved.getReason()).isEqualTo(ChatReportReason.ABUSE);
    assertThat(saved.getReasonDetail()).isEqualTo("반복적인 욕설");

    assertThat(response.chatReportId()).isEqualTo(saved.getId());
    assertThat(response.chatRoomId()).isEqualTo(roomId);
    assertThat(response.reason()).isEqualTo(ChatReportReason.ABUSE);
    assertThat(response.createdAt()).isEqualTo(saved.getCreatedAt());
  }

  @Test
  @DisplayName("사정사가 신고하면 reported_id는 고객(user_id)이 된다")
  void report_asAdjuster_savesCustomerAsReported() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    service.report(adjusterId, roomId, request("FRAUD", null));

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReporterId()).isEqualTo(adjusterId);
    assertThat(reportCaptor.getValue().getReportedId()).isEqualTo(userId);
  }

  @Test
  @DisplayName("종료(CLOSED)된 방도 신고가 접수된다 — CHAT_ROOM_CLOSED 가드를 넣으면 안 된다")
  void report_closedRoom_isAccepted() {
    ChatRoom room = givenRoom(ChatRoomStatus.CLOSED);
    givenSavePersists();

    ChatReportResponse response = service.report(userId, roomId, request("SPAM", null));

    assertThat(response.chatReportId()).isNotNull();
    assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
    then(chatReportRepository).should().save(any(ChatReport.class));
  }

  @Test
  @DisplayName("같은 사용자가 같은 방을 3번 신고하면 3건 모두 접수되고 신고 id가 서로 다르다")
  void report_threeTimesBySameReporter_createsThreeRows() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    ChatReportResponse first = service.report(userId, roomId, request("SPAM", null));
    ChatReportResponse second = service.report(userId, roomId, request("SPAM", null));
    ChatReportResponse third = service.report(userId, roomId, request("ABUSE", "또 발생"));

    then(chatReportRepository).should(times(3)).save(any(ChatReport.class));
    assertThat(first.chatReportId())
        .isNotEqualTo(second.chatReportId())
        .isNotEqualTo(third.chatReportId());
    assertThat(second.chatReportId()).isNotEqualTo(third.chatReportId());
  }

  @Test
  @DisplayName("신고해도 채팅방의 상태·마지막 메시지·읽음 커서·updated_at은 전혀 변하지 않는다")
  void report_doesNotMutateChatRoom() {
    ChatRoom room = givenRoom(ChatRoomStatus.ACTIVE);
    LocalDateTime lastMessageAt = LocalDateTime.of(2026, 8, 1, 10, 0);
    LocalDateTime userRead = LocalDateTime.of(2026, 8, 1, 11, 0);
    LocalDateTime adjusterRead = LocalDateTime.of(2026, 8, 1, 12, 0);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 1, 13, 0);
    ReflectionTestUtils.setField(room, "lastMessage", "마지막 메시지");
    ReflectionTestUtils.setField(room, "lastMessageAt", lastMessageAt);
    ReflectionTestUtils.setField(room, "userLastReadAt", userRead);
    ReflectionTestUtils.setField(room, "adjusterLastReadAt", adjusterRead);
    ReflectionTestUtils.setField(room, "updatedAt", updatedAt);
    givenSavePersists();

    service.report(userId, roomId, request("PRIVACY_VIOLATION", "개인정보를 요구했습니다"));

    assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(room.getLastMessage()).isEqualTo("마지막 메시지");
    assertThat(room.getLastMessageAt()).isEqualTo(lastMessageAt);
    assertThat(room.getUserLastReadAt()).isEqualTo(userRead);
    assertThat(room.getAdjusterLastReadAt()).isEqualTo(adjusterRead);
    assertThat(room.getUpdatedAt()).isEqualTo(updatedAt);
    then(chatRoomRepository).should(never()).save(any(ChatRoom.class));
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404) — 참여자 검사보다 먼저 평가된다")
  void report_roomNotFound_throws404() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.report(userId, roomId, request("SPAM", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @Test
  @DisplayName("참여자가 아니면 CHAT_NOT_A_MEMBER(403)이고 아무것도 저장하지 않는다")
  void report_notAMember_throws403() {
    givenRoom(ChatRoomStatus.ACTIVE);

    assertThatThrownBy(() -> service.report(UUID.randomUUID(), roomId, request("SPAM", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @ParameterizedTest(name = "reason=[{0}]")
  @NullSource
  @ValueSource(strings = {"", "   "})
  @DisplayName("reason이 없거나 공백이면 MISSING_REQUIRED_FIELD(400)")
  void report_reasonMissing_throws400(String reason) {
    givenRoom(ChatRoomStatus.ACTIVE);

    assertThatThrownBy(() -> service.report(userId, roomId, request(reason, null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @ParameterizedTest(name = "reason=[{0}]")
  @ValueSource(strings = {"HARASSMENT", "abuse", "Abuse", "spam", "OTHERS"})
  @DisplayName("정의되지 않은 사유·소문자 사유는 VALIDATION_ERROR(400) — 대소문자 정규화는 하지 않는다")
  void report_unsupportedReason_throws400(String reason) {
    givenRoom(ChatRoomStatus.ACTIVE);

    assertThatThrownBy(() -> service.report(userId, roomId, request(reason, null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @ParameterizedTest(name = "reason_detail=[{0}]")
  @NullSource
  @ValueSource(strings = {"", "   ", "\t\n "})
  @DisplayName("reason=OTHER인데 상세가 없거나 공백뿐이면 MISSING_REQUIRED_FIELD(400)")
  void report_otherWithoutDetail_throws400(String reasonDetail) {
    givenRoom(ChatRoomStatus.ACTIVE);

    assertThatThrownBy(() -> service.report(userId, roomId, request("OTHER", reasonDetail)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @Test
  @DisplayName("reason=OTHER에 상세를 채우면 접수된다")
  void report_otherWithDetail_isAccepted() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    service.report(userId, roomId, request("OTHER", "직접입력 사유"));

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReason()).isEqualTo(ChatReportReason.OTHER);
    assertThat(reportCaptor.getValue().getReasonDetail()).isEqualTo("직접입력 사유");
  }

  @ParameterizedTest(name = "reason_detail=[{0}]")
  @NullSource
  @ValueSource(strings = {"", "   "})
  @DisplayName("OTHER가 아닌 사유는 상세가 없거나 공백뿐이어도 접수되고 상세는 null로 정규화된다")
  void report_nonOtherWithoutDetail_normalizesToNull(String reasonDetail) {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    service.report(userId, roomId, request("SPAM", reasonDetail));

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReasonDetail()).isNull();
  }

  @Test
  @DisplayName("reason_detail이 정확히 500자면 접수된다(경계 통과)")
  void report_detailExactly500_isAccepted() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    service.report(userId, roomId, request("SPAM", DETAIL_500));

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReasonDetail()).hasSize(500);
  }

  @Test
  @DisplayName("reason_detail이 501자면 VALIDATION_ERROR(400)")
  void report_detail501_throws400() {
    givenRoom(ChatRoomStatus.ACTIVE);

    assertThatThrownBy(() -> service.report(userId, roomId, request("SPAM", DETAIL_500 + "가")))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    then(chatReportRepository).should(never()).save(any(ChatReport.class));
  }

  @Test
  @DisplayName("앞뒤 공백을 포함해 500자를 넘겨도 trim 후 500자면 접수된다")
  void report_detailPaddedTo500_isAcceptedAfterTrim() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    assertThatCode(() -> service.report(userId, roomId, request("SPAM", "   " + DETAIL_500 + "   ")))
        .doesNotThrowAnyException();

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReasonDetail()).isEqualTo(DETAIL_500);
  }

  @Test
  @DisplayName("reason_detail 앞뒤 공백은 제거된 값으로 저장된다")
  void report_detailIsTrimmedBeforeSave() {
    givenRoom(ChatRoomStatus.ACTIVE);
    givenSavePersists();

    service.report(userId, roomId, request("ABUSE", "  욕설이 있었습니다  "));

    then(chatReportRepository).should().save(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getReasonDetail()).isEqualTo("욕설이 있었습니다");
  }
}
