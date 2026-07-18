-- =====================================================================
-- V19 — 자격신청 필드 확장(specialties 복수화·phone 추가)
-- 버전 주의: V16·V17은 채팅 PR(#112), V18은 알림 PR(#117)이 점유하므로
-- 중복 회피를 위해 V19로 배치한다(V16~V18 건너뜀은 의도된 gap).
-- speciality(단수 varchar) → specialties(text[])로 복수화하고 phone(연락처)을 추가한다.
-- 기존 speciality 값은 단일 원소 배열로 이관 후 컬럼을 제거한다(기존 행 보존).
-- =====================================================================
ALTER TABLE adjuster_applications ADD COLUMN phone varchar(20);
ALTER TABLE adjuster_applications ADD COLUMN specialties text[];
UPDATE adjuster_applications SET specialties = ARRAY[speciality] WHERE speciality IS NOT NULL;
ALTER TABLE adjuster_applications DROP COLUMN speciality;
