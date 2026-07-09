package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionRequest;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.dto.ReportDetailResponse;
import com.soma.backend.domain.report.service.ProposalQueryService;
import com.soma.backend.domain.report.service.ReportCommandService;
import com.soma.backend.domain.report.service.ReportQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CurrentUserId;

/**
 * 고객(user) 리포트 플로우 5개 API(design.md §1, §6). 인가는 @CurrentUserId(로그인) +
 * 서비스 레이어 소유 검증(design.md §8)이 담당한다.
 */
@RestController
@RequiredArgsConstructor
public class ReportController {

  private final ReportCommandService reportCommandService;
  private final ReportQueryService reportQueryService;
  private final ProposalQueryService proposalQueryService;

  @PostMapping("/reports")
  public ResponseEntity<ApiResponse<CreateReportResponse>> create(
      @CurrentUserId UUID userId, @RequestBody CreateReportRequest request) {
    CreateReportResponse data = reportCommandService.createReport(userId, request);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new ApiResponse<>("202", "리포트 생성을 시작했습니다.", data));
  }

  @GetMapping("/reports")
  public ResponseEntity<ApiResponse<ReportCardListResponse>> list(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(ApiResponse.ok(reportQueryService.getUserReports(userId, status, page, size)));
  }

  @GetMapping("/reports/{reportId}")
  public ResponseEntity<ApiResponse<ReportDetailResponse>> detail(
      @CurrentUserId UUID userId, @PathVariable UUID reportId) {
    return ResponseEntity.ok(ApiResponse.ok(reportQueryService.getDetail(userId, reportId)));
  }

  @GetMapping("/reports/{reportId}/proposals")
  public ResponseEntity<ApiResponse<ProposalListResponse>> proposals(
      @CurrentUserId UUID userId,
      @PathVariable UUID reportId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(
        ApiResponse.ok(proposalQueryService.getProposals(userId, reportId, page, size)));
  }

  @PatchMapping("/reports/{reportId}/proposals/{proposalId}")
  public ResponseEntity<ApiResponse<ProposalDecisionResponse>> decide(
      @CurrentUserId UUID userId,
      @PathVariable UUID reportId,
      @PathVariable UUID proposalId,
      @RequestBody ProposalDecisionRequest request) {
    ProposalDecisionResponse data =
        reportCommandService.decide(userId, reportId, proposalId, request.status());
    return ResponseEntity.ok(ApiResponse.ok(data));
  }
}
