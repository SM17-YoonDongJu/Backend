package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.service.ReviewedReportQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.ActiveAdjuster;

/** API#5 내 검수 내역 + 상단 통계 컨트롤러. 열람 전용이라 인증·미인증 사정사 모두 허용. */
@RestController
@RequiredArgsConstructor
public class ReviewedReportController {

  private final ReviewedReportQueryService reviewedReportQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/reviewed-reports")
  public ResponseEntity<ApiResponse<ReviewedReportListResponse>> reviewedReports(
      @ActiveAdjuster UUID adjusterId,
      @RequestParam(required = false, defaultValue = "ALL") String status,
      @RequestParam(required = false) String month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = PageRequest.of(page, size);
    ReviewedReportListResponse result =
        reviewedReportQueryService.getReviewedReports(adjusterId, status, month, pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
