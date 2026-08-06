package com.soma.backend.domain.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 프록시 업로드 응답. 직렬화 시 s3_url로 변환된다.
 *
 * @param s3Url 서버가 S3에 저장한 object의 최종 URL(https://{bucket}.s3.{region}.amazonaws.com/{key}).
 *              후속 도메인 요청(리포트 문서·자격 신청·프로필)에 이 값을 그대로 실어 보낸다.
 */
public record UploadResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String s3Url) {
}
