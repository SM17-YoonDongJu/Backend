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
import com.soma.backend.domain.user.repository.ActiveReportRow;
import com.soma.backend.domain.user.repository.ProposalItemRow;
import com.soma.backend.domain.user.repository.UserDashboardRepository;

/**
 * 고객 홈 BFF 집계 유스케이스(CQRS, 조회 전용). report_count는 기존 집계를 재사용하고, 대표 활성 리포트·
 * 제안 요약은 대시보드 읽기 모델에서 조립한다(사정사 홈 AdjusterHomeQueryService 관례). 대상은 항상 요청
 * principal 본인이라 별도 인가 없이 userId로만 조회한다.
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

    return new UserDashboardResponse(reportCount, activeReport, proposalSummary);
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

  /** 제안(거절 제외)이 하나도 없으면 null. 견적 집계는 min·max·중앙값 평균, 견적 있는 제안만 대상. */
  private ProposalSummary toProposalSummary(List<ProposalItemRow> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    Integer minAmount = rows.stream()
        .map(ProposalItemRow::estimateMinAmount)
        .filter(amount -> amount != null)
        .min(Integer::compareTo)
        .orElse(null);
    Integer maxAmount = rows.stream()
        .map(ProposalItemRow::estimateMaxAmount)
        .filter(amount -> amount != null)
        .max(Integer::compareTo)
        .orElse(null);
    List<ProposalSummary.Item> items = rows.stream().map(this::toItem).toList();
    return new ProposalSummary(rows.size(), minAmount, maxAmount, averageMidpoint(rows), items);
  }

  /** 제안가 평균 — 각 제안 중앙값((min+max)/2)의 평균을 반올림. min·max 둘 다 있는 제안만 집계, 없으면 null. */
  private Integer averageMidpoint(List<ProposalItemRow> rows) {
    List<Integer> midpoints = rows.stream()
        .filter(row -> row.estimateMinAmount() != null && row.estimateMaxAmount() != null)
        .map(row -> (row.estimateMinAmount() + row.estimateMaxAmount()) / 2)
        .toList();
    if (midpoints.isEmpty()) {
      return null;
    }
    int sum = midpoints.stream().mapToInt(Integer::intValue).sum();
    return (int) Math.round((double) sum / midpoints.size());
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
}
