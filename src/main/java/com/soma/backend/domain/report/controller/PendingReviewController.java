package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.dto.ReviewReportResponse;
import com.soma.backend.domain.report.service.PendingReviewQueryService;
import com.soma.backend.domain.report.service.ReportHoldCommandService;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.ActiveAdjuster;

/**
 * 검수 대기 요약(API#1)·목록(API#2)·보류 추가(API#3)·검수 반영(API#4) 컨트롤러.
 *
 * <p>인가: 열람(GET)은 인증·미인증 사정사 모두 허용, 사정 기능(hold·검수 반영)은
 * {@code CERTIFICATED_ADJUSTER}만 허용한다. {@link ActiveAdjuster}는 userId 주입 전용.
 */
@RestController
@RequiredArgsConstructor
public class PendingReviewController {

  private final PendingReviewQueryService pendingReviewQueryService;
  private final ReportHoldCommandService reportHoldCommandService;
  private final ReportReviewCommandService reportReviewCommandService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/reports/pending-review/summary")
  public ResponseEntity<ApiResponse<PendingReviewSummaryResponse>> summary(@ActiveAdjuster UUID adjusterId) {
    return ResponseEntity.ok(ApiResponse.ok(pendingReviewQueryService.getSummary()));
  }

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/reports/pending-review")
  public ResponseEntity<ApiResponse<PendingReviewListResponse>> pendingReview(
      @ActiveAdjuster UUID adjusterId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String accidentType,
      @RequestParam(required = false) String region,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = PageRequest.of(page, size);
    PendingReviewListResponse result =
        pendingReviewQueryService.getPendingReviewList(status, accidentType, region, adjusterId, pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")
  @PostMapping("/reports/{reportId}/hold")
  public ResponseEntity<ApiResponse<HoldResponse>> addHold(
      @ActiveAdjuster UUID adjusterId, @PathVariable UUID reportId) {
    HoldResponse result = reportHoldCommandService.addHold(reportId, adjusterId);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")
  @PatchMapping("/reports/{reportId}")
  public ResponseEntity<ApiResponse<ReviewReportResponse>> reviewReport(
      @ActiveAdjuster UUID adjusterId, @PathVariable UUID reportId, @RequestBody ReviewReportRequest request) {
    ReviewReportResponse result = reportReviewCommandService.review(adjusterId, reportId, request);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
