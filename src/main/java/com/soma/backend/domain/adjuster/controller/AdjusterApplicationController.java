package com.soma.backend.domain.adjuster.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationRequest;
import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.service.AdjusterApplicationCommandService;
import com.soma.backend.domain.adjuster.service.AdjusterApplicationQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 손해사정사 자격 신청 API(Notion API 명세). 인증된 사용자 본인의 신청 접수·상태 조회를 다룬다.
 * 대상은 항상 principal 본인이며(@AuthenticationPrincipal), 별도 역할 게이트 없이 로그인만 요구한다.
 */
@RestController
@RequestMapping("/users/adjuster-applications")
@RequiredArgsConstructor
public class AdjusterApplicationController {

  private final AdjusterApplicationCommandService adjusterApplicationCommandService;
  private final AdjusterApplicationQueryService adjusterApplicationQueryService;

  /**
   * 자격 신청 접수. 201 + 신청 ID·상태.
   */
  @PostMapping
  public ResponseEntity<ApiResponse<CreateAdjusterApplicationResponse>> apply(
      @AuthenticationPrincipal CustomUserDetails principal,
      @Valid @RequestBody CreateAdjusterApplicationRequest request) {
    CreateAdjusterApplicationResponse data = adjusterApplicationCommandService.apply(principal.getUserId(), request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created("자격 신청이 접수되었습니다.", data));
  }

  /**
   * 내 자격 신청 상태 조회. 200 + 신청 상세(문서 심사 상태 포함).
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<AdjusterApplicationResponse>> getMyApplication(
      @AuthenticationPrincipal CustomUserDetails principal) {
    AdjusterApplicationResponse data = adjusterApplicationQueryService.getMyApplication(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(data));
  }
}
