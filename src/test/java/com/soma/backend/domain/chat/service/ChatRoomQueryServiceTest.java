package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** 채팅방 목록(설계서 §4 ①) 단위 테스트. me 기준 상대방 결정·unread null 방어를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomQueryServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @InjectMocks
  private ChatRoomQueryService service;

  @Test
  @DisplayName("me가 user이면 counterpart는 adjuster(id·닉네임)이다")
  void listMyRooms_meIsUser_counterpartIsAdjuster() {
    UUID me = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ChatRoomStatus.ACTIVE, ReviewStatus.SENT,
        me, adjusterId, "고객닉네임", "사정사닉네임", "마지막 메시지", LocalDateTime.now(), 3L);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me);

    ChatRoomSummaryResponse summary = response.rooms().get(0);
    assertThat(summary.counterpart().userId()).isEqualTo(adjusterId);
    assertThat(summary.counterpart().name()).isEqualTo("사정사닉네임");
    assertThat(summary.unreadCount()).isEqualTo(3L);
  }

  @Test
  @DisplayName("me가 adjuster이면 counterpart는 user(id·닉네임)이다")
  void listMyRooms_meIsAdjuster_counterpartIsUser() {
    UUID userId = UUID.randomUUID();
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null,
        userId, me, "고객닉네임", "사정사닉네임", null, null, null);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me);

    ChatRoomSummaryResponse summary = response.rooms().get(0);
    assertThat(summary.counterpart().userId()).isEqualTo(userId);
    assertThat(summary.counterpart().name()).isEqualTo("고객닉네임");
  }

  @Test
  @DisplayName("unread_count가 null이면 0으로 방어한다")
  void listMyRooms_nullUnreadCount_defaultsToZero() {
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me);

    assertThat(response.rooms().get(0).unreadCount()).isZero();
  }

  @Test
  @DisplayName("검색 방(제안 없음)은 report_id·report_review_id·review_status 모두 null로 노출된다")
  void listMyRooms_searchRoom_exposesNullReportFields() {
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, 0L);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomSummaryResponse summary = service.listMyRooms(me).rooms().get(0);

    assertThat(summary.reportId()).isNull();
    assertThat(summary.reportReviewId()).isNull();
    assertThat(summary.reviewStatus()).isNull();
  }
}
