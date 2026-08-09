package com.soma.backend.infra.sqs;

/**
 * OCR 트리거 SQS 메시지 계약(design.md §4, §7). 필드는 camelCase(Java record)로 선언하고, 발행 시
 * 전역 Jackson 설정(SNAKE_CASE)을 통해 snake_case JSON(job_id, s3_key, content_type, user_ref,
 * doc_type_hint, claim_id, report_id, attachment_id, doc_index, doc_total, uploaded_at)으로 나간다.
 * PII(개인식별정보)는 포함하지 않는다 — S3 키·참조 UUID·문서 메타데이터만 담는다.
 *
 * <p>report_id·attachment_id 추가(2026-07-10): Spring이 리포트 shell·첨부를 생성하므로, FastAPI가
 * OCR·AI 결과로 해당 report/report_attachment 행을 UPDATE(생성 아님)하도록 참조 키를 함께 싣는다.
 * doc_index·doc_total 추가: 한 청구의 문서를 1건씩 발행하므로, FastAPI가 doc_total 대비 완료 개수로
 * 리포트 생성(fan-in) 시점을 판별한다. 계약 변경이므로 소비자(ocr_worker) 측 contracts.py와 합의 필요.
 *
 * @param jobId        문서별 신규 UUIDv4 문자열(멱등 키)
 * @param s3Key        S3 오브젝트 키(호스트 이후 경로)
 * @param contentType  문서 MIME 타입
 * @param userRef      인증된 사용자 userId(UUID) 문자열
 * @param docTypeHint  문서 신고 유형 힌트(nullable)
 * @param claimId      연관된 UserClaim.id(UUID) 문자열(nullable)
 * @param reportId     연관된 REPORTS.id(UUID) 문자열 — 결과 UPDATE 대상
 * @param attachmentId 연관된 REPORT_ATTACHMENTS.id(UUID) 문자열 — 결과 UPDATE 대상
 * @param docIndex     이 청구 내 문서 순번(1-based, nullable)
 * @param docTotal     이 청구의 총 문서 수(nullable) — FastAPI가 OCR 완료(fan-in) 판별에 사용
 * @param uploadedAt   업로드 시각, ISO-8601 UTC 문자열
 */
public record OcrJob(
    String jobId,
    String s3Key,
    String contentType,
    String userRef,
    String docTypeHint,
    String claimId,
    String reportId,
    String attachmentId,
    Integer docIndex,
    Integer docTotal,
    String uploadedAt) {
}
