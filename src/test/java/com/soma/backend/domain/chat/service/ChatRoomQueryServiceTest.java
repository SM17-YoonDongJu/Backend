package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomCursor;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3ObjectStorage;

/**
 * 채팅방 목록·단건 상세(설계서 §4 ①) 단위 테스트. me 기준 상대방 결정·unread null 방어와
 * 목록 커서 페이지네이션(size clamp·has_next·커서 round-trip·잘못된 커서 400)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomQueryServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private S3ObjectStorage s3ObjectStorage;

  @InjectMocks
  private ChatRoomQueryService service;

  @Test
  @DisplayName("me가 user이면 counterpart는 adjuster(id·닉네임·아바타)이고 리포트 정보(case_no·report_type_label)를 노출한다")
  void listMyRooms_meIsUser_counterpartIsAdjuster() {
    UUID me = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ChatRoomStatus.ACTIVE, ReviewStatus.SENT,
        "20260520-017", AccidentType.TRAFFIC, me, adjusterId, "고객닉네임", "사정사닉네임", "고객아바타", "사정사아바타",
        "마지막 메시지", LocalDateTime.now(), 3L, null);
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of(row));
    given(s3ObjectStorage.presignedGetUrl("사정사아바타")).willReturn("사정사아바타");

    ChatRoomListResponse response = service.listMyRooms(me, null, 20);

    ChatRoomSummaryResponse summary = response.rooms().get(0);
    assertThat(summary.counterpart().userId()).isEqualTo(adjusterId);
    assertThat(summary.counterpart().name()).isEqualTo("사정사닉네임");
    assertThat(summary.counterpart().avatarUrl()).isEqualTo("사정사아바타");
    assertThat(summary.caseNo()).isEqualTo("20260520-017");
    assertThat(summary.reportTypeLabel()).isEqualTo(AccidentType.TRAFFIC);
    assertThat(summary.unreadCount()).isEqualTo(3L);
  }

  @Test
  @DisplayName("me가 adjuster이면 counterpart는 user(id·닉네임)이다")
  void listMyRooms_meIsAdjuster_counterpartIsUser() {
    UUID userId = UUID.randomUUID();
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null, null, null,
        userId, me, "고객닉네임", "사정사닉네임", null, null, null, null, null, null);
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me, null, 20);

    ChatRoomSummaryResponse summary = response.rooms().get(0);
    assertThat(summary.counterpart().userId()).isEqualTo(userId);
    assertThat(summary.counterpart().name()).isEqualTo("고객닉네임");
  }

  @Test
  @DisplayName("unread_count가 null이면 0으로 방어한다")
  void listMyRooms_nullUnreadCount_defaultsToZero() {
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null, null, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null, null, null, null);
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me, null, 20);

    assertThat(response.rooms().get(0).unreadCount()).isZero();
  }

  @Test
  @DisplayName("검색 방(제안 없음)은 report_id·proposal_id·match_status 모두 null로 노출된다")
  void listMyRooms_searchRoom_exposesNullReportFields() {
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null, null, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null, null, 0L, null);
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of(row));

    ChatRoomSummaryResponse summary = service.listMyRooms(me, null, 20).rooms().get(0);

    assertThat(summary.reportId()).isNull();
    assertThat(summary.proposalId()).isNull();
    assertThat(summary.matchStatus()).isNull();
  }

  @Test
  @DisplayName("getRoom: 참여한 방이면 상세(상대·roomStatus·matchStatus·report_type_label·createdAt)를 반환한다")
  void getRoom_returnsDetail() {
    UUID me = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.of(2026, 5, 20, 9, 0);
    ChatRoomListRow row = new ChatRoomListRow(
        chatRoomId, UUID.randomUUID(), UUID.randomUUID(), ChatRoomStatus.ACTIVE, ReviewStatus.SENT,
        "20260520-017", AccidentType.TRAFFIC, me, adjusterId, "고객닉네임", "사정사닉네임", "고객아바타", "사정사아바타",
        "마지막 메시지", LocalDateTime.now(), 1L, createdAt);
    given(chatRoomRepository.findMyRoomRow(me, chatRoomId)).willReturn(Optional.of(row));

    ChatRoomDetailResponse detail = service.getRoom(me, chatRoomId);

    assertThat(detail.chatRoomId()).isEqualTo(chatRoomId);
    assertThat(detail.counterpart().userId()).isEqualTo(adjusterId);
    assertThat(detail.counterpart().name()).isEqualTo("사정사닉네임");
    assertThat(detail.roomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(detail.matchStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(detail.reportTypeLabel()).isEqualTo(AccidentType.TRAFFIC);
    assertThat(detail.createdAt()).isEqualTo(createdAt);
  }

  @Test
  @DisplayName("getRoom: 없는 방/미참여면 CHAT_ROOM_NOT_FOUND(404)")
  void getRoom_notFound() {
    UUID me = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    given(chatRoomRepository.findMyRoomRow(me, chatRoomId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRoom(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  private static ChatRoomListRow rowAt(UUID me, UUID chatRoomId, LocalDateTime lastMessageAt) {
    return new ChatRoomListRow(
        chatRoomId, null, null, ChatRoomStatus.ACTIVE, null, null, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null, lastMessageAt, 0L, null);
  }

  private static List<ChatRoomListRow> rows(UUID me, int count) {
    return IntStream.range(0, count)
        .mapToObj(index -> rowAt(me, UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0).minusMinutes(index)))
        .toList();
  }

  private static String encode(String raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest(name = "size={0} → 리포지토리 limit={1}")
  @CsvSource({"0, 21", "-5, 21", "51, 51", "30, 31", "50, 51", "1, 2"})
  @DisplayName("size clamp: 0·음수는 기본 20, 50 초과는 50 — 리포지토리에는 pageSize + 1이 넘어간다")
  void listMyRooms_clampsSize(int size, int expectedLimit) {
    UUID me = UUID.randomUUID();
    given(chatRoomRepository.findMyRoomRows(eq(me), isNull(), anyInt())).willReturn(List.of());

    service.listMyRooms(me, null, size);

    verify(chatRoomRepository).findMyRoomRows(me, null, expectedLimit);
  }

  @Test
  @DisplayName("리포지토리가 pageSize+1행을 주면 has_next=true, rooms는 pageSize개, next_cursor는 마지막 행 인코딩")
  void listMyRooms_hasNext_returnsPageAndCursorOfLastRow() {
    UUID me = UUID.randomUUID();
    List<ChatRoomListRow> fetched = rows(me, 4);
    given(chatRoomRepository.findMyRoomRows(me, null, 4)).willReturn(fetched);

    ChatRoomListResponse response = service.listMyRooms(me, null, 3);

    assertThat(response.hasNext()).isTrue();
    assertThat(response.rooms()).hasSize(3);
    ChatRoomListRow last = fetched.get(2);
    LocalDateTime at = last.lastMessageAt();
    String expected = encode(at.toEpochSecond(ZoneOffset.UTC) + "_" + at.getNano() + "_" + last.chatRoomId());
    assertThat(response.nextCursor()).isEqualTo(expected);
    // presigned URL 생성은 페이지에 담긴 행에만 일어난다(초과 조회한 1행은 제외)
    verify(s3ObjectStorage, times(3)).presignedGetUrl(any());
  }

  @Test
  @DisplayName("리포지토리가 pageSize행 이하를 주면 has_next=false, next_cursor=null")
  void listMyRooms_noNext_returnsNullCursor() {
    UUID me = UUID.randomUUID();
    given(chatRoomRepository.findMyRoomRows(me, null, 4)).willReturn(rows(me, 3));

    ChatRoomListResponse response = service.listMyRooms(me, null, 3);

    assertThat(response.hasNext()).isFalse();
    assertThat(response.nextCursor()).isNull();
    assertThat(response.rooms()).hasSize(3);
  }

  @Test
  @DisplayName("방이 0개면 rooms=[]·has_next=false·next_cursor=null")
  void listMyRooms_empty() {
    UUID me = UUID.randomUUID();
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of());

    ChatRoomListResponse response = service.listMyRooms(me, null, 20);

    assertThat(response.rooms()).isEmpty();
    assertThat(response.hasNext()).isFalse();
    assertThat(response.nextCursor()).isNull();
  }

  @Test
  @DisplayName("빈 문자열 커서는 첫 페이지로 처리한다")
  void listMyRooms_blankCursor_isFirstPage() {
    UUID me = UUID.randomUUID();
    given(chatRoomRepository.findMyRoomRows(me, null, 21)).willReturn(List.of());

    service.listMyRooms(me, "", 20);

    verify(chatRoomRepository).findMyRoomRows(me, null, 21);
  }

  @Test
  @DisplayName("커서 round-trip(timestamp형): 받은 next_cursor를 넘기면 리포지토리가 같은 (lastMessageAt, id)를 받는다")
  void listMyRooms_cursorRoundTrip_timestamp() {
    UUID me = UUID.randomUUID();
    UUID lastId = UUID.randomUUID();
    LocalDateTime lastAt = LocalDateTime.of(2026, 8, 1, 10, 20, 30, 123_456_000);
    List<ChatRoomListRow> fetched = List.of(
        rowAt(me, UUID.randomUUID(), lastAt.plusHours(1)),
        rowAt(me, lastId, lastAt),
        rowAt(me, UUID.randomUUID(), null));
    given(chatRoomRepository.findMyRoomRows(me, null, 3)).willReturn(fetched);
    String nextCursor = service.listMyRooms(me, null, 2).nextCursor();

    given(chatRoomRepository.findMyRoomRows(eq(me), any(ChatRoomCursor.class), eq(3))).willReturn(List.of());
    service.listMyRooms(me, nextCursor, 2);

    ArgumentCaptor<ChatRoomCursor> captor = ArgumentCaptor.forClass(ChatRoomCursor.class);
    verify(chatRoomRepository, times(2)).findMyRoomRows(eq(me), captor.capture(), eq(3));
    // 1회차는 첫 페이지(null 커서), 2회차가 복원된 커서다
    assertThat(captor.getAllValues()).containsExactly(null, new ChatRoomCursor(lastAt, lastId));
  }

  @Test
  @DisplayName("커서 round-trip(null형): 마지막 행이 메시지 없는 방이면 N_ 커서가 되고 lastMessageAt=null로 복원된다")
  void listMyRooms_cursorRoundTrip_nullSegment() {
    UUID me = UUID.randomUUID();
    UUID lastId = UUID.randomUUID();
    List<ChatRoomListRow> fetched = List.of(
        rowAt(me, UUID.randomUUID(), null), rowAt(me, lastId, null), rowAt(me, UUID.randomUUID(), null));
    given(chatRoomRepository.findMyRoomRows(me, null, 3)).willReturn(fetched);
    String nextCursor = service.listMyRooms(me, null, 2).nextCursor();
    assertThat(nextCursor).isEqualTo(encode("N_" + lastId));

    given(chatRoomRepository.findMyRoomRows(eq(me), any(ChatRoomCursor.class), eq(3))).willReturn(List.of());
    service.listMyRooms(me, nextCursor, 2);

    ArgumentCaptor<ChatRoomCursor> captor = ArgumentCaptor.forClass(ChatRoomCursor.class);
    verify(chatRoomRepository, times(2)).findMyRoomRows(eq(me), captor.capture(), eq(3));
    // 1회차는 첫 페이지(null 커서), 2회차가 복원된 커서다
    assertThat(captor.getAllValues()).containsExactly(null, new ChatRoomCursor(null, lastId));
  }

  @Test
  @DisplayName("Base64가 아닌 커서는 INVALID_REQUEST")
  void listMyRooms_nonBase64Cursor_throwsInvalidRequest() {
    UUID me = UUID.randomUUID();

    assertThatThrownBy(() -> service.listMyRooms(me, "%%%not-base64%%%", 20))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    verify(chatRoomRepository, never()).findMyRoomRows(any(), any(), anyInt());
  }

  @ParameterizedTest(name = "raw={0}")
  @ValueSource(strings = {
    "onlyonetoken",
    "X_550e8400-e29b-41d4-a716-446655440000",
    "N_not-a-uuid",
    "abc_1_550e8400-e29b-41d4-a716-446655440000",
    "N_1_550e8400-e29b-41d4-a716-446655440000",
    "1700000000_2000000000_550e8400-e29b-41d4-a716-446655440000",
    "1700000000_0_not-a-uuid"})
  @DisplayName("형식이 어긋난 커서(토큰 수·N 표식·숫자·UUID·nano 범위)는 INVALID_REQUEST")
  void listMyRooms_malformedCursor_throwsInvalidRequest(String raw) {
    UUID me = UUID.randomUUID();

    assertThatThrownBy(() -> service.listMyRooms(me, encode(raw), 20))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    verify(chatRoomRepository, never()).findMyRoomRows(any(), any(), anyInt());
  }
}
