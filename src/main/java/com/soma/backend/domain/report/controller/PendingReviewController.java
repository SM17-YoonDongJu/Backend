package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.HoldReportRequest;
import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.dto.ReviewReportResponse;
import com.soma.backend.domain.report.dto.ReviewWorkspaceResponse;
import com.soma.backend.domain.report.service.PendingReviewQueryService;
import com.soma.backend.domain.report.service.ReportHoldCommandService;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.domain.report.service.ReviewWorkspaceQueryService;
import com.soma.backend.global.common.PageRequests;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 검수 대기 요약(API#1)·목록(API#2)·보류 추가(API#3)·검수 반영(API#4) 컨트롤러.
 *
 * <p>인가는 메서드의 {@code @PreAuthorize}가 담당한다. 미인증(401)과 role 불일치(403) 모두
 * 메서드 시큐리티가 판정하며, 예외는 {@code GlobalExceptionHandler}가 매핑한다. 조회(요약·목록)는
 * CERTIFICATED_ADJUSTER·UNCERTIFICATED_ADJUSTER 모두 허용하고,
 * 쓰기(보류·검수 반영)는 CERTIFICATED_ADJUSTER만 허용한다.
 * 인증된 사용자의 userId는 {@code @AuthenticationPrincipal}로 주입한다.
 */
@RestController
@RequiredArgsConstructor
public class PendingReviewController {

  private final PendingReviewQueryService pendingReviewQueryService;
  private final ReportHoldCommandService reportHoldCommandService;
  private final ReportReviewCommandService reportReviewCommandService;
  private final ReviewWorkspaceQueryService reviewWorkspaceQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/reports/pending-review/summary")
  public ResponseEntity<ApiResponse<PendingReviewSummaryResponse>> summary(
      @AuthenticationPrincipal CustomUserDetails principal) {
    return ResponseEntity.ok(ApiResponse.ok(pendingReviewQueryService.getSummary(principal.getUserId())));
  }

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/reports/pending-review")
  public ResponseEntity<ApiResponse<PendingReviewListResponse>> pendingReview(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String accidentType,
      @RequestParam(required = false) String region,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequests.ofOneBased(page, size);
    PendingReviewListResponse result =
        pendingReviewQueryService.getPendingReviewList(
            status, accidentType, region, principal.getUserId(), pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")
  @GetMapping("/reports/{reportId}/review")
  public ResponseEntity<ApiResponse<ReviewWorkspaceResponse>> reviewWorkspace(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID reportId) {
    ReviewWorkspaceResponse result =
        reviewWorkspaceQueryService.getReviewWorkspace(reportId, principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")
  @PostMapping("/reports/{reportId}/hold")
  public ResponseEntity<ApiResponse<HoldResponse>> addHold(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID reportId,
      @RequestBody HoldReportRequest request) {
    HoldResponse result = reportHoldCommandService.addHold(reportId, principal.getUserId(), request);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")
  @PatchMapping("/reports/{reportId}")
  public ResponseEntity<ApiResponse<ReviewReportResponse>> reviewReport(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID reportId,
      @RequestBody ReviewReportRequest request) {
    ReviewReportResponse result = reportReviewCommandService.review(principal.getUserId(), reportId, request);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
