package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.AdjusterReviewListResponse;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewRequest;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewResponse;
import com.soma.backend.domain.report.service.AdjusterReviewCommandService;
import com.soma.backend.domain.report.service.AdjusterReviewQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 사정사 평가(후기) 등록·조회. 등록은 인증 사용자(자격=해당 사정사 수임 이력), 조회는 인증 사용자면 열람한다.
 */
@RestController
@RequestMapping("/adjusters/{adjusterId}/reviews")
@RequiredArgsConstructor
public class AdjusterReviewController {

  private final AdjusterReviewCommandService adjusterReviewCommandService;
  private final AdjusterReviewQueryService adjusterReviewQueryService;

  /** 평가 등록. 201. 400 범위 오류 / 403 자격 없음 / 409 중복. */
  @PostMapping
  public ResponseEntity<ApiResponse<CreateAdjusterReviewResponse>> createReview(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID adjusterId,
      @Valid @RequestBody CreateAdjusterReviewRequest request) {
    CreateAdjusterReviewResponse result =
        adjusterReviewCommandService.createReview(principal.getUserId(), adjusterId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("정상 처리되었습니다.", result));
  }

  /** 평가 목록(page 기본 1, size 기본 10). 200. 404 사정사 없음. */
  @GetMapping
  public ResponseEntity<ApiResponse<AdjusterReviewListResponse>> getReviews(
      @PathVariable UUID adjusterId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    AdjusterReviewListResponse result = adjusterReviewQueryService.getReviews(adjusterId, page, size);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
