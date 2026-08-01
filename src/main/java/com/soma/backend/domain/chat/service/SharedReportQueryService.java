package com.soma.backend.domain.chat.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.chat.dto.SharedReportResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅방 공유 리포트 조회(design.md). 방 참여자에게 담당 사정사의 검수 결과를 사용자 노출용으로 제공한다.
 * 인가·앵커는 chatRoom이 소유하며(§2), 검수 본문·쟁점은 REPORT_REVIEWS/REPORT_ISSUES에서 읽어 §5로 병합한다.
 */
@Service
@RequiredArgsConstructor
public class SharedReportQueryService {

  private final ChatRoomRepository chatRoomRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final AdjusterProfileRepository adjusterProfileRepository;

  /** GET /chats/{chatRoomId}/shared-report — 방 참여자만. 검색 방(reportReviewId null)은 PROPOSAL_NOT_FOUND. */
  @Transactional(readOnly = true)
  public SharedReportResponse getSharedReport(UUID me, UUID chatRoomId) {
    ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!chatRoom.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    UUID reportReviewId = chatRoom.getReportReviewId();
    if (reportReviewId == null) {
      throw new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND);
    }
    ReportReview review = reportReviewRepository.findById(reportReviewId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
    Report report = reportRepository.findById(review.getReportId())
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    List<ReportIssue> aiIssues = reportIssueRepository.findAllByReportId(review.getReportId());
    AdjusterProfile adjusterProfile =
        adjusterProfileRepository.findByUserId(review.getAdjusterId()).orElse(null);
    List<SharedReportResponse.Issue> issues = resolveIssues(review, aiIssues);
    return SharedReportResponse.from(chatRoom, review, report, issues, adjusterProfile);
  }

  /**
   * §5 쟁점 병합·resolve·필터(사용자 노출용). overlay 없는 AI 쟁점·EXCLUDED는 노출하지 않고, ACCEPTED/MODIFIED는
   * overlay 우선(fallback ai)으로 단일 값 resolve한다. ADDED(사정사 신규)는 AI 매칭분(원본 순서) 뒤에 덧붙인다.
   */
  private List<SharedReportResponse.Issue> resolveIssues(ReportReview review, List<ReportIssue> aiIssues) {
    Map<UUID, ReportReviewIssue> overlayByReportIssueId = new HashMap<>();
    List<ReportReviewIssue> added = new ArrayList<>();
    for (ReportReviewIssue overlay : review.getIssues()) {
      if (overlay.getReportIssueId() == null) {
        added.add(overlay);
      } else {
        overlayByReportIssueId.put(overlay.getReportIssueId(), overlay);
      }
    }
    List<SharedReportResponse.Issue> issues = new ArrayList<>();
    for (ReportIssue ai : aiIssues) {
      ReportReviewIssue overlay = overlayByReportIssueId.get(ai.getId());
      if (overlay == null || overlay.getReviewStatus() == IssueReviewStatus.EXCLUDED) {
        continue;
      }
      issues.add(toIssue(ai, overlay));
    }
    for (ReportReviewIssue addedIssue : added) {
      issues.add(toAddedIssue(addedIssue));
    }
    return issues;
  }

  /** AI 쟁점 + 사정사 오버레이 resolve — title·description·impactAmount는 overlay 우선, tags는 AI 원본. */
  private SharedReportResponse.Issue toIssue(ReportIssue ai, ReportReviewIssue overlay) {
    return new SharedReportResponse.Issue(
        ai.getId(),
        overlay.getId(),
        overlay.getTitle() != null ? overlay.getTitle() : ai.getTitle(),
        overlay.getAdjusterOpinion(),
        overlay.getDescription() != null ? overlay.getDescription() : ai.getDescription(),
        overlay.getImpactAmount() != null ? overlay.getImpactAmount() : ai.getImpactAmount(),
        overlay.getReviewStatus().name(),
        ai.getTags() != null ? ai.getTags() : List.of());
  }

  /** 사정사 신규(ADDED) 쟁점 — AI 원본이 없으므로 issueId=null, tags=빈 배열. */
  private SharedReportResponse.Issue toAddedIssue(ReportReviewIssue added) {
    return new SharedReportResponse.Issue(
        null,
        added.getId(),
        added.getTitle(),
        added.getAdjusterOpinion(),
        added.getDescription(),
        added.getImpactAmount(),
        added.getReviewStatus().name(),
        List.of());
  }
}
