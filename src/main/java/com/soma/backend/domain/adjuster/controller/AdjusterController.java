package com.soma.backend.domain.adjuster.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterDetailResponse;
import com.soma.backend.domain.adjuster.dto.AdjusterListResponse;
import com.soma.backend.domain.adjuster.service.AdjusterListQueryService;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.response.ApiResponse;

/**
 * 손해사정사 공개 조회. 인증된 사용자면 누구나(고객 포함) 사정사 목록/검색과 임의 사정사의 공개 상세를 열람한다
 * — {@code adjusterId}는 사정사의 user_id다. 본인 전용 마이페이지/프로필은 AdjusterProfileController가 담당한다.
 */
@RestController
@RequiredArgsConstructor
public class AdjusterController {

  private final AdjusterProfileQueryService adjusterProfileQueryService;
  private final AdjusterListQueryService adjusterListQueryService;

  /**
   * 사정사 목록/검색(GET /adjusters). keyword·specialty·region·sort로 필터·정렬하고 page(1-based)·size로 페이징한다.
   * 고객 파인더 무한 스크롤과 홈 추천(sort=rating 첫 페이지)이 공유한다.
   */
  @PreAuthorize("isAuthenticated()")
  @GetMapping("/adjusters")
  public ResponseEntity<ApiResponse<AdjusterListResponse>> getAdjusters(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String specialty,
      @RequestParam(required = false) String region,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int size) {
    AdjusterListResponse result =
        adjusterListQueryService.getAdjusters(keyword, specialty, region, sort, page, size);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("isAuthenticated()")
  @GetMapping("/adjusters/{adjusterId}")
  public ResponseEntity<ApiResponse<AdjusterDetailResponse>> getAdjusterDetail(
      @PathVariable UUID adjusterId) {
    AdjusterDetailResponse result = adjusterProfileQueryService.getAdjusterDetail(adjusterId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
