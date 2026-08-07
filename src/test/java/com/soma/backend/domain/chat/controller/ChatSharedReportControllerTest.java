package com.soma.backend.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.chat.dto.SharedReportResponse;
import com.soma.backend.domain.chat.service.ChatConsultationCommandService;
import com.soma.backend.domain.chat.service.ChatReadService;
import com.soma.backend.domain.chat.service.ChatRoomQueryService;
import com.soma.backend.domain.chat.service.SharedReportQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * GET /chats/{chatRoomId}/shared-report 슬라이스 테스트. 실제 Jackson 전역 설정(snake_case)과 시큐리티
 * 필터체인을 태워 응답 바디 스냅샷(snake_case 필드명)과 인가(401 미인증·403 비참여·404 공유리포트 없음)를 검증한다.
 */
@WebMvcTest(ChatRoomController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("ChatRoomController 공유리포트 조회 슬라이스 테스트")
class ChatSharedReportControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChatRoomQueryService chatRoomQueryService;

  @MockitoBean
  private ChatConsultationCommandService chatConsultationCommandService;

  @MockitoBean
  private ChatReadService chatReadService;

  @MockitoBean
  private SharedReportQueryService sharedReportQueryService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("참여자면 200과 함께 공유 리포트 본문을 snake_case 필드로 반환한다")
  void getSharedReport_asMember_returns200SnakeCaseBody() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID reviewIssueId = UUID.randomUUID();

    SharedReportResponse.Adjuster adjuster =
        new SharedReportResponse.Adjuster(adjusterId, "김사정", 18, List.of("후유장해", "교통사고"));
    SharedReportResponse.Estimate estimate = new SharedReportResponse.Estimate(12_000_000, 18_000_000);
    SharedReportResponse.Issue issue = new SharedReportResponse.Issue(
        issueId, reviewIssueId, "장해등급 과소", "인정 의견", "설명", 3_000_000, "ACCEPTED", List.of("교통"));
    SharedReportResponse response = new SharedReportResponse(
        chatRoomId, reportId, proposalId, "20260520-017", "traffic", "교통사고 후유장해",
        "COUNSELING", "SENT",
        LocalDateTime.of(2026, 5, 20, 9, 0), LocalDateTime.of(2026, 5, 22, 10, 0),
        "검수 요약 인용문", adjuster, estimate, 5_000_000, List.of(issue), 1,
        List.of("보장A"), List.of("특약B"), List.of("근거C"));
    given(sharedReportQueryService.getSharedReport(eq(userId), eq(chatRoomId))).willReturn(response);

    // When & Then
    mockMvc.perform(get("/chats/{chatRoomId}/shared-report", chatRoomId).with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.chat_room_id").value(chatRoomId.toString()))
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.proposal_id").value(proposalId.toString()))
        .andExpect(jsonPath("$.data.case_no").value("20260520-017"))
        .andExpect(jsonPath("$.data.accident_type").value("traffic"))
        .andExpect(jsonPath("$.data.title").value("교통사고 후유장해"))
        .andExpect(jsonPath("$.data.report_status").value("COUNSELING"))
        .andExpect(jsonPath("$.data.review_status").value("SENT"))
        .andExpect(jsonPath("$.data.report_updated_at").value("2026-05-20T09:00:00"))
        .andExpect(jsonPath("$.data.submitted_at").value("2026-05-22T10:00:00"))
        .andExpect(jsonPath("$.data.summary").value("검수 요약 인용문"))
        .andExpect(jsonPath("$.data.offered_amount").value(5_000_000L))
        .andExpect(jsonPath("$.data.issue_count").value(1))
        .andExpect(jsonPath("$.data.adjuster.adjuster_id").value(adjusterId.toString()))
        .andExpect(jsonPath("$.data.adjuster.name").value("김사정"))
        .andExpect(jsonPath("$.data.adjuster.career").value(18))
        .andExpect(jsonPath("$.data.adjuster.specialties[0]").value("후유장해"))
        .andExpect(jsonPath("$.data.estimate.min").value(12_000_000L))
        .andExpect(jsonPath("$.data.estimate.max").value(18_000_000L))
        .andExpect(jsonPath("$.data.applicable_guarantees[0]").value("보장A"))
        .andExpect(jsonPath("$.data.omitted_special_contract[0]").value("특약B"))
        .andExpect(jsonPath("$.data.basis_terms_precedents[0]").value("근거C"))
        .andExpect(jsonPath("$.data.issues[0].issue_id").value(issueId.toString()))
        .andExpect(jsonPath("$.data.issues[0].review_issue_id").value(reviewIssueId.toString()))
        .andExpect(jsonPath("$.data.issues[0].title").value("장해등급 과소"))
        .andExpect(jsonPath("$.data.issues[0].adjuster_opinion").value("인정 의견"))
        .andExpect(jsonPath("$.data.issues[0].impact_amount").value(3_000_000L))
        .andExpect(jsonPath("$.data.issues[0].review_status").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.issues[0].tags[0]").value("교통"));
  }

  @Test
  @DisplayName("미인증이면 401 LOGIN_REQUIRED")
  void getSharedReport_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/chats/{chatRoomId}/shared-report", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("방 참여자가 아니면 403 CHAT_NOT_A_MEMBER")
  void getSharedReport_notMember_returns403() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    given(sharedReportQueryService.getSharedReport(any(), any()))
        .willThrow(new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER));

    // When & Then
    mockMvc.perform(get("/chats/{chatRoomId}/shared-report", chatRoomId).with(authenticatedAs(userId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CHAT_NOT_A_MEMBER"));
  }

  @Test
  @DisplayName("공유 리포트가 없는 방(검색 방·제안 없음)이면 404 PROPOSAL_NOT_FOUND")
  void getSharedReport_noSharedReport_returns404() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    given(sharedReportQueryService.getSharedReport(any(), any()))
        .willThrow(new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));

    // When & Then
    mockMvc.perform(get("/chats/{chatRoomId}/shared-report", chatRoomId).with(authenticatedAs(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_FOUND"));
  }
}
