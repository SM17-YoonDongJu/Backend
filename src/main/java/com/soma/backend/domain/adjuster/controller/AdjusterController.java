package com.soma.backend.domain.adjuster.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterDetailResponse;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.response.ApiResponse;

/**
 * 손해사정사 공개 조회. 인증된 사용자면 누구나(고객 포함) 임의 사정사의 공개 상세를 열람한다
 * — {@code adjusterId}는 사정사의 user_id다. 본인 전용 마이페이지/프로필은 AdjusterProfileController가 담당한다.
 */
@RestController
@RequiredArgsConstructor
public class AdjusterController {

  private final AdjusterProfileQueryService adjusterProfileQueryService;

  @PreAuthorize("isAuthenticated()")
  @GetMapping("/adjusters/{adjusterId}")
  public ResponseEntity<ApiResponse<AdjusterDetailResponse>> getAdjusterDetail(
      @PathVariable UUID adjusterId) {
    AdjusterDetailResponse result = adjusterProfileQueryService.getAdjusterDetail(adjusterId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
