package com.soma.backend.domain.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.repository.ChatRoomCursor;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3ObjectStorage;

/**
 * 채팅방 목록·단건 상세 조회(설계서 §4 ①). 상대방 이름을 me 기준으로 결정하고 안읽음 수를 포함한다.
 * 목록은 keyset 커서 페이지네이션이다(정렬 {@code last_message_at DESC NULLS LAST, id DESC}).
 */
@Service
@RequiredArgsConstructor
public class ChatRoomQueryService {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 50;
  private static final String NULL_TIMESTAMP_TOKEN = "N";

  private final ChatRoomRepository chatRoomRepository;
  private final S3ObjectStorage s3ObjectStorage;

  @Transactional(readOnly = true)
  public ChatRoomListResponse listMyRooms(UUID me, String cursor, int size) {
    int pageSize = clampSize(size);
    ChatRoomCursor parsed = StringUtils.hasText(cursor) ? parseCursor(cursor) : null;

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(me, parsed, pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    List<ChatRoomListRow> page = hasNext ? rows.subList(0, pageSize) : rows;

    // presigned URL 생성(resolveCounterpart)은 페이지에 담긴 행에만 일어난다.
    List<ChatRoomSummaryResponse> rooms = page.stream()
        .map(row -> toSummary(row, me))
        .toList();
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
    return new ChatRoomListResponse(rooms, nextCursor, hasNext);
  }

  /** GET /chats/{chatRoomId} — 딥링크/새로고침 시 채팅 헤더용 단건 상세. 미참여·미존재면 404. */
  @Transactional(readOnly = true)
  public ChatRoomDetailResponse getRoom(UUID me, UUID chatRoomId) {
    ChatRoomListRow row = chatRoomRepository.findMyRoomRow(me, chatRoomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    return new ChatRoomDetailResponse(
        row.chatRoomId(),
        row.reportId(),
        row.reportReviewId(),
        row.status(),
        row.reviewStatus(),
        row.caseNo(),
        row.accidentType(),
        resolveCounterpart(row, me),
        row.lastMessage(),
        row.lastMessageAt(),
        unread(row),
        row.createdAt());
  }

  private ChatRoomSummaryResponse toSummary(ChatRoomListRow row, UUID me) {
    return new ChatRoomSummaryResponse(
        row.chatRoomId(),
        row.reportId(),
        row.reportReviewId(),
        row.status(),
        row.reviewStatus(),
        row.caseNo(),
        row.accidentType(),
        resolveCounterpart(row, me),
        row.lastMessage(),
        row.lastMessageAt(),
        unread(row));
  }

  /** 상대방(고객↔사정사)을 me 기준으로 결정한다. me가 user면 상대는 adjuster, 아니면 user. */
  private ChatRoomSummaryResponse.Counterpart resolveCounterpart(ChatRoomListRow row, UUID me) {
    boolean iAmUser = me.equals(row.userId());
    UUID counterpartId = iAmUser ? row.adjusterId() : row.userId();
    String counterpartName = iAmUser ? row.adjusterNickname() : row.userNickname();
    String counterpartAvatarUrl = iAmUser ? row.adjusterAvatarUrl() : row.userAvatarUrl();
    return new ChatRoomSummaryResponse.Counterpart(
        counterpartId, counterpartName, s3ObjectStorage.presignedGetUrl(counterpartAvatarUrl));
  }

  private long unread(ChatRoomListRow row) {
    return row.unreadCount() == null ? 0L : row.unreadCount();
  }

  private int clampSize(int size) {
    if (size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  /** 마지막 행 위치를 opaque 커서로 만든다. 값이 있으면 {@code {epochSec}_{nano}_{uuid}}, null 구간이면 {@code N_{uuid}}. */
  private String encodeCursor(ChatRoomListRow row) {
    LocalDateTime lastMessageAt = row.lastMessageAt();
    String raw = lastMessageAt == null
        ? NULL_TIMESTAMP_TOKEN + "_" + row.chatRoomId()
        : lastMessageAt.toEpochSecond(ZoneOffset.UTC) + "_" + lastMessageAt.getNano() + "_" + row.chatRoomId();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private ChatRoomCursor parseCursor(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("_", 3);
      if (parts.length == 3) {
        LocalDateTime lastMessageAt = LocalDateTime.ofEpochSecond(
            Long.parseLong(parts[0]), Integer.parseInt(parts[1]), ZoneOffset.UTC);
        return new ChatRoomCursor(lastMessageAt, UUID.fromString(parts[2]));
      }
      if (parts.length == 2 && NULL_TIMESTAMP_TOKEN.equals(parts[0])) {
        return new ChatRoomCursor(null, UUID.fromString(parts[1]));
      }
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    } catch (IllegalArgumentException | DateTimeException ex) {
      // Base64 오류·NumberFormatException·UUID 파싱 오류·범위 밖 nano 모두 400으로 통일한다.
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
  }
}
