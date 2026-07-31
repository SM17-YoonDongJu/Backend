package com.soma.backend.domain.upload.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.upload.dto.UploadResponse;
import com.soma.backend.domain.upload.entity.UploadPurpose;
import com.soma.backend.domain.upload.service.UploadService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 문서·이미지의 서버 경유(프록시) 업로드 진입점. multipart/form-data로 파일 바이트와 purpose를 수신해 서버가
 * 검증(형식·용량·매직바이트)한 뒤 S3에 저장하고 최종 object URL을 반환한다. SecurityConfig의 permitAll 목록에
 * 없어 인증 필수 경로이므로 principal은 non-null이다(인증 게이트 용도로만 받고 key 구성에는 쓰지 않는다).
 */
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

  private final UploadService uploadService;

  /**
   * 파일 업로드. multipart 파트 file(바이너리)·purpose(용도)를 받아 200 + s3_url을 반환한다.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<UploadResponse>> upload(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam("file") MultipartFile file,
      @RequestParam("purpose") String purpose) {

    UploadResponse result = uploadService.upload(file, parsePurpose(purpose));
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /**
   * purpose 문자열(소문자 계약값 avatar·report_document 등)을 UploadPurpose로 변환한다. 스프링 기본 enum
   * 변환은 상수명(대문자)을 대소문자 무시로 매칭해 환경마다 동작이 달라지므로, @JsonCreator와 동일한
   * from()으로 엄격히 파싱한다. 알 수 없는 값은 400 INVALID_REQUEST.
   */
  private UploadPurpose parsePurpose(String purpose) {
    try {
      return UploadPurpose.from(purpose);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
  }
}
