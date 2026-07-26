package com.soma.backend.domain.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.soma.backend.domain.upload.entity.UploadPurpose;

/**
 * Presigned 업로드 URL 발급 요청. 전역 SNAKE_CASE 직렬화로 file_name→fileName,
 * content_type→contentType이 자동 매핑되며, purpose는 UploadPurpose의 @JsonCreator가
 * 소문자 값(report_document 등)을 역직렬화한다.
 *
 * @param fileName    원본 파일명(현재 key에는 사용하지 않고 계약·로깅용으로만 수신)
 * @param contentType MIME 타입(purpose별 화이트리스트로 검증)
 * @param purpose     업로드 용도
 */
public record CreatePresignedUploadRequest(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Size(max = 100) String contentType,
    @NotNull UploadPurpose purpose) {
}
