package com.soma.backend.domain.chat.entity;

/**
 * CHATROOM.status 값 객체 — 방 생명주기만 나타낸다.
 * 상담 수락/거절 결정은 REPORT_REVIEWS.status가 소유하며 여기에 중복하지 않는다.
 */
public enum ChatRoomStatus {
  ACTIVE,
  CLOSED
}
