-- =====================================================================
-- V18 — 인앱 알림함(NOTIFICATIONS)
-- 버전 주의: V16·V17은 채팅 도메인 PR(#112)의 마이그레이션이 점유하므로,
-- 중복 회피를 위해 알림은 V18로 배치한다(V16·V17 건너뜀은 의도된 gap).
-- FE 인앱 알림 페이지용. 개별 알림 레코드(제목·본문·읽음 여부)를 저장한다.
-- 발송 토큰(device_tokens)·수신 설정(notification_settings)과 별개로, 과거 알림
-- 목록·읽음 상태를 관리한다. 관계: USERS 1:N NOTIFICATIONS.
-- 알림 생성(producer: 검수완료·채팅 등 이벤트→row insert)은 별도 티켓 범위다(본 마이그레이션은 스키마만).
-- type은 애플리케이션 @Enumerated(STRING)에 맞춰 varchar로 둔다(CHECK 미부여, V1 규약).
-- created_at은 기존 백엔드 소유 테이블과 동일하게 timestamp(+DEFAULT now())로 둔다
-- (엔티티는 LocalDateTime 매핑 → ddl-auto=validate 정합).
-- =====================================================================
CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    type varchar(30) NOT NULL,
    title varchar(255) NOT NULL,
    body text,
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_user_is_read ON notifications (user_id, is_read);
