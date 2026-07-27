package com.soma.backend.domain.user.repository;

import java.util.UUID;

/** 대시보드 todos의 대표 안읽음 채팅 1건 projection(상대=사정사). lastMessage는 방의 마지막 메시지 미리보기. */
public record UnreadChatRow(
    UUID chatRoomId,
    String adjusterNickname,
    String lastMessage) {
}
