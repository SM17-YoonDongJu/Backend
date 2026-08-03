package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3UploadService;

/** 채팅방 목록·단건 상세(설계서 §4 ①) 단위 테스트. me 기준 상대방 결정·unread null 방어를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatRoomQueryServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @Mock
  private S3UploadService s3UploadService;

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
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));
    given(s3UploadService.presignedGetUrl("사정사아바타")).willReturn("사정사아바타");

    ChatRoomListResponse response = service.listMyRooms(me);

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
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null, null, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null, null, null, null);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomListResponse response = service.listMyRooms(me);

    assertThat(response.rooms().get(0).unreadCount()).isZero();
  }

  @Test
  @DisplayName("검색 방(제안 없음)은 report_id·proposal_id·match_status 모두 null로 노출된다")
  void listMyRooms_searchRoom_exposesNullReportFields() {
    UUID me = UUID.randomUUID();
    ChatRoomListRow row = new ChatRoomListRow(
        UUID.randomUUID(), null, null, ChatRoomStatus.ACTIVE, null, null, null,
        me, UUID.randomUUID(), "고객닉네임", "사정사닉네임", null, null, null, null, 0L, null);
    given(chatRoomRepository.findMyRoomRows(me)).willReturn(List.of(row));

    ChatRoomSummaryResponse summary = service.listMyRooms(me).rooms().get(0);

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
}
