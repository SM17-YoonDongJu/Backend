package com.soma.backend.infra.kafka;

/**
 * OCR 트리거 Kafka 메시지 계약(design.md §4, §7 — 고정 시그니처, 임의 변경 금지).
 * 필드는 camelCase(Java record)로 선언하고, 발행 시 전역 Jackson 설정(SNAKE_CASE)을 통해
 * snake_case JSON(job_id, s3_key, content_type, user_ref, doc_type_hint, claim_id, uploaded_at)으로 나간다.
 * PII(개인식별정보)는 포함하지 않는다 — S3 키·사용자 참조(UUID 문자열)·문서 메타데이터만 담는다.
 *
 * @param jobId       문서별 신규 UUIDv4 문자열
 * @param s3Key       S3 오브젝트 키(호스트 이후 경로)
 * @param contentType 문서 MIME 타입
 * @param userRef     인증된 사용자 userId(UUID) 문자열
 * @param docTypeHint 문서 신고 유형 힌트(nullable)
 * @param claimId     연관된 UserClaim.id(UUID) 문자열(nullable)
 * @param uploadedAt  업로드 시각, ISO-8601 UTC 문자열
 */
public record OcrJob(
    String jobId,
    String s3Key,
    String contentType,
    String userRef,
    String docTypeHint,
    String claimId,
    String uploadedAt) {
}
