-- OCR 트리거 아웃박스 테이블/인덱스를 kafka_ → ocr_ 로 리네임(#208 브로커 Kafka→SQS 전환의 마무리).
-- 브로커 이름(kafka)을 스키마에 박았다가 SQS 전환으로 stale해진 것을 용도(OCR 발행) 기반 이름으로 교정한다.
-- 컬럼·데이터·제약은 그대로 보존되는 메타데이터 RENAME이다. OcrOutboxEvent @Table 변경과 반드시 같은
-- 배포로 나가야 ddl-auto: validate가 통과한다. 최초 생성 마이그레이션은 V13__kafka_outbox.sql.
ALTER TABLE kafka_outbox_events RENAME TO ocr_outbox_events;
ALTER INDEX idx_kafka_outbox_status_created RENAME TO idx_ocr_outbox_status_created;
-- 테이블 RENAME은 암묵 생성된 PK 제약 이름까지 바꾸지는 않는다(kafka_outbox_events_pkey가 그대로 남는다).
-- 기능상 무해하나 스키마에 옛 브로커 이름이 남으므로 함께 정리한다.
ALTER TABLE ocr_outbox_events RENAME CONSTRAINT kafka_outbox_events_pkey TO ocr_outbox_events_pkey;
