package com.soma.backend.domain.upload.dto;

/**
 * Presigned 업로드 URL 발급 응답. 직렬화 시 upload_url, s3_url로 변환된다.
 *
 * @param uploadUrl presigned PUT URL(TTL 5분). FE가 이 URL로 파일 바이너리를 PUT하며, PUT 요청에
 *                  Content-Type 헤더를 요청 content_type과 동일하게 실어야 한다(서명 바인딩).
 * @param s3Url     업로드 후 최종 object URL(https://{bucket}.s3.{region}.amazonaws.com/{key})
 */
public record PresignedUploadResponse(String uploadUrl, String s3Url) {
}
