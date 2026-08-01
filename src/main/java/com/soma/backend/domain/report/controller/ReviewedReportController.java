package com.soma.backend.domain.report.controller;


import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.service.ReviewedReportQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * API#5 내 검수 내역 + 상단 통계 컨트롤러.
 *
 * <p>미인증(401)과 role 불일치(403) 모두 {@code @PreAuthorize}(메서드 시큐리티)가 판정하고,
 * 예외는 {@code GlobalExceptionHandler}가 매핑한다. 인증된 사용자의 userId는
 * {@code @AuthenticationPrincipal}로 주입한다.
 * 조회이므로 CERTIFICATED_ADJUSTER·UNCERTIFICATED_ADJUSTER 모두 허용한다.
 */
@RestController
@RequiredArgsConstructor
public class ReviewedReportController {

  private final ReviewedReportQueryService reviewedReportQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/reviewed-reports")
  public ResponseEntity<ApiResponse<ReviewedReportListResponse>> reviewedReports(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(required = false, defaultValue = "ALL") String status,
      @RequestParam(required = false) String month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = PageRequest.of(page, size);
    ReviewedReportListResponse result =
        reviewedReportQueryService.getReviewedReports(principal.getUserId(), status, month, pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
