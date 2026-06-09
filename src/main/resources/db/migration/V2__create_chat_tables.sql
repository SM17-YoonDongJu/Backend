-- V2__create_chat_tables.sql
-- WebSocket(STOMP) 채팅 도메인 테이블 생성.
-- users 테이블 부재로 sender_id / user_id 에는 FK 미설정 (가정 3).

CREATE TABLE chat_room (
    id          UUID         PRIMARY KEY,
    title       VARCHAR(100),
    type        VARCHAR(20)  NOT NULL,            -- DIRECT | GROUP
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_room_member (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL REFERENCES chat_room(id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL,            -- FK 없음(users 테이블 부재, 가정 3)
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_read_at TIMESTAMPTZ,                     -- unread 계산용(향후)
    CONSTRAINT uq_room_member UNIQUE (room_id, user_id)
);

CREATE TABLE chat_message (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL REFERENCES chat_room(id) ON DELETE CASCADE,
    sender_id   UUID         NOT NULL,            -- FK 없음(가정 3)
    type        VARCHAR(20)  NOT NULL DEFAULT 'TALK',  -- TALK | JOIN | LEAVE
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_member_user          ON chat_room_member (user_id);
CREATE INDEX idx_chat_member_room          ON chat_room_member (room_id);
CREATE INDEX idx_chat_message_room_created ON chat_message (room_id, created_at DESC, id DESC);
