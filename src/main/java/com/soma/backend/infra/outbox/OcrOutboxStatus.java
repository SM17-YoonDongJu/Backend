package com.soma.backend.infra.outbox;

/** OCR 아웃박스 이벤트 발행 상태(ocr_outbox_events.status). */
public enum OcrOutboxStatus {
  PENDING,
  SENT,
  FAILED
}
