package com.soma.backend.domain.user.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.user.dto.UserDashboardResponse;
import com.soma.backend.domain.user.dto.UserDashboardResponse.ActiveReport;
import com.soma.backend.domain.user.dto.UserDashboardResponse.ProposalSummary;
import com.soma.backend.domain.user.dto.UserDashboardResponse.Todos;
import com.soma.backend.domain.user.dto.UserDashboardResponse.UnreadChat;
import com.soma.backend.domain.user.repository.ActiveReportRow;
import com.soma.backend.domain.user.repository.ProposalItemRow;
import com.soma.backend.domain.user.repository.UnreadChatRow;
import com.soma.backend.domain.user.repository.UserDashboardRepository;

/**
 * 고객 홈 BFF 집계 유스케이스(CQRS, 조회 전용). report_count는 기존 집계를 재사용하고, 대표 활성 리포트·
 * 제안 요약·"지금 할 일"(todos)은 대시보드 읽기 모델에서 조립한다(사정사 홈 AdjusterHomeQueryService 관례).
 * 대상은 항상 요청 principal 본인이라 별도 인가 없이 userId로만 조회한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserDashboardQueryService {

  private final ReportRepository reportRepository;
  private final UserDashboardRepository userDashboardRepository;

  public UserDashboardResponse getDashboard(UUID userId) {
    long reportCount = reportRepository.countByUserId(userId);

    ActiveReport activeReport = null;
    ProposalSummary proposalSummary = null;
    ActiveReportRow activeRow = userDashboardRepository.findLatestActiveReport(userId).orElse(null);
    if (activeRow != null) {
      List<ProposalItemRow> itemRows = userDashboardRepository.findProposalItems(activeRow.reportId());
      LocalDateTime firstReviewedAt = userDashboardRepository.findFirstReviewedAt(activeRow.reportId());
      activeReport = toActiveReport(activeRow, firstReviewedAt, itemRows.size());
      proposalSummary = toProposalSummary(itemRows);
    }

    Todos todos = new Todos(
        userDashboardRepository.countUnreadProposals(userId),
        userDashboardRepository.countUnreadReviewCompleted(userId),
        userDashboardRepository.findTopUnreadChat(userId).map(this::toUnreadChat).orElse(null));

    return new UserDashboardResponse(reportCount, activeReport, proposalSummary, todos);
  }

  private ActiveReport toActiveReport(ActiveReportRow row, LocalDateTime firstReviewedAt, long proposalCount) {
    return new ActiveReport(
        row.reportId(),
        row.title(),
        row.accidentType() == null ? null : row.accidentType().getValue(),
        row.status().name(),
        row.createdAt(),
        firstReviewedAt,
        proposalCount);
  }

  /**
   * 제안(거절 제외)이 없거나 집계할 견적(min·max 둘 다 있는 제안)이 하나도 없으면 null. 금액 필드에 null을
   * 섞지 않도록, 평균을 낼 수 없으면(avgAmount=null) 요약 객체 자체를 내리지 않는다. avgAmount가 존재하면
   * min·max도 항상 존재한다(그 제안이 둘 다 제공).
   */
  private ProposalSummary toProposalSummary(List<ProposalItemRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    Long avgAmount = averageMidpoint(rows);
    if (avgAmount == null) {
      return null;
    }
    Long minAmount = rows.stream()
        .map(ProposalItemRow::estimateMinAmount)
        .filter(amount -> amount != null)
        .min(Long::compareTo)
        .orElse(null);
    Long maxAmount = rows.stream()
        .map(ProposalItemRow::estimateMaxAmount)
        .filter(amount -> amount != null)
        .max(Long::compareTo)
        .orElse(null);
    List<ProposalSummary.Item> items = rows.stream().map(this::toItem).toList();
    return new ProposalSummary(rows.size(), minAmount, maxAmount, avgAmount, items);
  }

  /** 제안가 평균 — 각 제안 중앙값((min+max)/2)의 평균을 반올림. min·max 둘 다 있는 제안만 집계, 없으면 null. */
  private Long averageMidpoint(List<ProposalItemRow> rows) {
    List<Long> midpoints = rows.stream()
        .filter(row -> row.estimateMinAmount() != null && row.estimateMaxAmount() != null)
        .map(row -> (row.estimateMinAmount() + row.estimateMaxAmount()) / 2)
        .toList();
    if (midpoints.isEmpty()) {
      return null;
    }
    long sum = midpoints.stream().mapToLong(Long::longValue).sum();
    return Math.round((double) sum / midpoints.size());
  }

  private ProposalSummary.Item toItem(ProposalItemRow row) {
    String speciality = (row.specialties() == null || row.specialties().isEmpty())
        ? null : row.specialties().get(0);
    return new ProposalSummary.Item(
        row.proposalId(),
        row.adjusterId(),
        row.nickname(),
        row.career(),
        speciality,
        row.estimateMinAmount(),
        row.estimateMaxAmount());
  }

  private UnreadChat toUnreadChat(UnreadChatRow row) {
    return new UnreadChat(row.chatRoomId(), row.adjusterNickname(), row.lastMessage());
  }
}
