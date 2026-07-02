package com.soma.backend.report.presentation.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.ActiveAdjuster;
import com.soma.backend.report.application.PendingReviewQueryService;
import com.soma.backend.report.application.ReportHoldCommandService;
import com.soma.backend.report.application.ReportReviewCommandService;
import com.soma.backend.report.application.dto.HoldToggleResult;
import com.soma.backend.report.application.dto.PendingReviewItemResult;
import com.soma.backend.report.application.dto.ReviewReportCommand;
import com.soma.backend.report.application.dto.ReviewReportResult;
import com.soma.backend.report.presentation.dto.HoldToggleResponse;
import com.soma.backend.report.presentation.dto.PendingReviewListResponse;
import com.soma.backend.report.presentation.dto.PendingReviewSummaryResponse;
import com.soma.backend.report.presentation.dto.ReviewReportRequest;
import com.soma.backend.report.presentation.dto.ReviewReportResponse;

/**
 * 검수 대기 요약(API#1)·목록(API#2)·보류 토글(API#3)·검수 반영(API#4) 컨트롤러.
 * 인가 가드는 {@link ActiveAdjuster}(@ActiveAdjuster)가 담당(401/403).
 */
@RestController
@RequiredArgsConstructor
public class PendingReviewController {

  private final PendingReviewQueryService pendingReviewQueryService;
  private final ReportHoldCommandService reportHoldCommandService;
  private final ReportReviewCommandService reportReviewCommandService;

  @GetMapping("/reports/pending-review/summary")
  public ResponseEntity<ApiResponse<PendingReviewSummaryResponse>> summary(@ActiveAdjuster UUID adjusterId) {
    return ResponseEntity.ok(
        ApiResponse.ok(PendingReviewSummaryResponse.from(pendingReviewQueryService.getSummary())));
  }

  @GetMapping("/reports/pending-review")
  public ResponseEntity<ApiResponse<PendingReviewListResponse>> pendingReview(
      @ActiveAdjuster UUID adjusterId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String accidentType,
      @RequestParam(required = false) String region,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Pageable pageable = PageRequest.of(page, size);
    Page<PendingReviewItemResult> result =
        pendingReviewQueryService.getPendingReviewList(status, accidentType, region, adjusterId, pageable);
    return ResponseEntity.ok(ApiResponse.ok(PendingReviewListResponse.from(result)));
  }

  @PatchMapping("/reports/{reportId}/hold")
  public ResponseEntity<ApiResponse<HoldToggleResponse>> toggleHold(
      @ActiveAdjuster UUID adjusterId, @PathVariable UUID reportId) {
    HoldToggleResult result = reportHoldCommandService.toggle(reportId, adjusterId);
    return ResponseEntity.ok(ApiResponse.ok(HoldToggleResponse.from(result)));
  }

  @PatchMapping("/reports/{reportId}")
  public ResponseEntity<ApiResponse<ReviewReportResponse>> reviewReport(
      @ActiveAdjuster UUID adjusterId, @PathVariable UUID reportId, @RequestBody ReviewReportRequest request) {
    List<ReviewReportCommand.IssueReviewCommand> issues = request.issues() == null
        ? List.of()
        : request.issues().stream()
            .map(issue -> new ReviewReportCommand.IssueReviewCommand(
                issue.issueId(), issue.reviewStatus(), issue.adjusterOpinion(), issue.modifiedReason(),
                issue.excludedReason()))
            .toList();
    ReviewReportCommand command = new ReviewReportCommand(
        adjusterId, reportId, request.applicableGuarantees(), request.omittedSpecialContract(), issues,
        request.review(), request.status());
    ReviewReportResult result = reportReviewCommandService.review(command);
    return ResponseEntity.ok(ApiResponse.ok(ReviewReportResponse.from(result)));
  }
}
