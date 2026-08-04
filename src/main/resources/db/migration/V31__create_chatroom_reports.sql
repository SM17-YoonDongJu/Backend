-- =====================================================================
-- V31 — 채팅방 신고(CHATROOM_REPORTS) 신설.
-- 채팅방 참여자가 접수한 신고 기록(append-only, 생성 후 수정 없음).
-- 중복 신고를 허용하므로 (chat_room_id, reporter_id) 유니크 제약을 두지 않는다.
--   → 같은 사용자가 같은 방을 여러 번 신고하면 매번 새 행이 쌓인다.
-- reason: ChatReportReason enum 이름(대문자) 저장.
--         SPAM / ABUSE / FRAUD / PRIVACY_VIOLATION / OTHER. NOT NULL.
-- reason_detail: 상세 텍스트(선택). reason=OTHER면 필수·500자 이하는 애플리케이션이 강제한다
--         (DB varchar 제한을 걸면 초과 시 DataIntegrityViolationException → 409 DUPLICATE_RESOURCE로
--          잘못 매핑되므로 text로 두고 앱에서 400 VALIDATION_ERROR를 낸다).
-- reporter_id: 신고자. 방 참여자는 고객(chatroom.user_id)·사정사(chatroom.adjuster_id) 둘 다
--         가능하므로 user_id가 아니라 reporter_id로 둔다.
-- reported_id: 피신고자(상대방). 접수 시점에 ChatRoom.counterpartOf(reporterId)로 확정해 저장한다.
--         API 응답에는 노출하지 않으며, 향후 피신고 이력·통계 조회를 위한 확장 컬럼이다.
-- =====================================================================
CREATE TABLE chatroom_reports (
    id            uuid PRIMARY KEY,
    chat_room_id  uuid        NOT NULL REFERENCES chatroom (id),
    reporter_id   uuid        NOT NULL REFERENCES users (id),
    reported_id   uuid        NOT NULL REFERENCES users (id),
    reason        varchar(30) NOT NULL,
    reason_detail text,
    created_at    timestamp   NOT NULL DEFAULT now()
);

CREATE INDEX idx_chatroom_reports_room ON chatroom_reports (chat_room_id, created_at DESC);
CREATE INDEX idx_chatroom_reports_reporter ON chatroom_reports (reporter_id);
CREATE INDEX idx_chatroom_reports_reported ON chatroom_reports (reported_id);
