package com.soma.backend.domain.chat.entity;

/** CHATROOM_MESSAGES.message_type 값 객체. SYSTEM은 상담 시작·종결 등 서버 생성 안내. */
public enum ChatMessageType {
  TEXT,
  IMAGE,
  FILE,
  SYSTEM
}
