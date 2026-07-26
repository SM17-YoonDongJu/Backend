package com.soma.backend.domain.upload.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.upload.dto.CreatePresignedUploadRequest;
import com.soma.backend.domain.upload.dto.PresignedUploadResponse;
import com.soma.backend.domain.upload.service.UploadService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 문서·이미지 업로드용 presigned URL 발급 진입점. SecurityConfig의 permitAll 목록에 없어 인증 필수
 * 경로이므로 principal은 non-null이다. principal은 인증 게이트 용도로만 받고 key 구성에는 쓰지 않는다.
 */
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

  private final UploadService uploadService;

  /**
   * presigned PUT URL 발급. 200 + upload_url·s3_url.
   */
  @PostMapping
  public ResponseEntity<ApiResponse<PresignedUploadResponse>> createUploadUrl(
      @AuthenticationPrincipal CustomUserDetails principal,
      @Valid @RequestBody CreatePresignedUploadRequest request) {

    PresignedUploadResponse result = uploadService.createUploadUrl(request);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
