package com.soma.backend.report.presentation.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.ActiveAdjuster;
import com.soma.backend.report.application.ReviewedReportQueryService;
import com.soma.backend.report.application.dto.ReviewedReportItemResult;
import com.soma.backend.report.application.dto.ReviewedReportStatsResult;
import com.soma.backend.report.presentation.dto.ReviewedReportListResponse;

/** API#5 내 검수 내역 + 상단 통계 컨트롤러. */
@RestController
@RequiredArgsConstructor
public class ReviewedReportController {

  private final ReviewedReportQueryService reviewedReportQueryService;

  @GetMapping("/adjusters/me/reviewed-reports")
  public ResponseEntity<ApiResponse<ReviewedReportListResponse>> reviewedReports(
      @ActiveAdjuster UUID adjusterId,
      @RequestParam(required = false, defaultValue = "ALL") String status,
      @RequestParam(required = false) String month,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = PageRequest.of(page, size);
    ReviewedReportStatsResult stats = reviewedReportQueryService.getStats(adjusterId, month);
    Page<ReviewedReportItemResult> items =
        reviewedReportQueryService.getReviewedReports(adjusterId, status, month, pageable);
    return ResponseEntity.ok(ApiResponse.ok(ReviewedReportListResponse.from(stats, items)));
  }
}
