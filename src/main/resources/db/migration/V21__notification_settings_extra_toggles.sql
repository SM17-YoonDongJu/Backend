-- =====================================================================
-- V21 — 알림 설정 토글 4종 추가
-- 설정 없이 상시 발송이던 알림 유형에 수신 토글을 부여한다("설정 off면 해당 type 미발송").
-- type 대응: consult_accepted↔CONSULT_ACCEPTED · analysis_complete↔ANALYSIS_COMPLETE
--           · identity_verified↔IDENTITY_VERIFIED · review_deadline_soon↔REVIEW_DEADLINE_SOON
-- 버전 주의: V16·V17=채팅(#112), V19=자격신청(#121), V20=사정사검색(#2, 보류) 점유/예약 → V21.
-- 기능성 알림이라 기존 6키 관례대로 DEFAULT true.
-- =====================================================================
ALTER TABLE notification_settings
  ADD COLUMN consult_accepted boolean NOT NULL DEFAULT true,
  ADD COLUMN analysis_complete boolean NOT NULL DEFAULT true,
  ADD COLUMN identity_verified boolean NOT NULL DEFAULT true,
  ADD COLUMN review_deadline_soon boolean NOT NULL DEFAULT true;
