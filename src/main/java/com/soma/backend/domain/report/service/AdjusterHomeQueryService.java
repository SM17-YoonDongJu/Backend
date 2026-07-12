package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.AdjusterHomeResponse;
import com.soma.backend.domain.report.dto.AdjusterHomeResponse.Adjuster;
import com.soma.backend.domain.report.dto.AdjusterHomeResponse.InProgressCases;
import com.soma.backend.domain.report.dto.AdjusterHomeResponse.Rating;
import com.soma.backend.domain.report.dto.AdjusterHomeResponse.Summary;
import com.soma.backend.domain.report.repository.AdjusterIdentityRow;
import com.soma.backend.domain.report.repository.InProgressCaseRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * 사정사 홈 대시보드 집계 유스케이스(CQRS, 조회 전용). 검수 대기 풀 카운트(global)와 요청 사정사의
 * 진행 중·완료·상담·평점을 조합한다. 누적 검수·상담 전환·평점은 adjuster_profiles 비정규화 컬럼에서 읽고
 * (증가/집계 책임은 검수·상담·후기 write 로직), '이번 달 완료'만 월별 컬럼이 없어 report_reviews 실시간 집계다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterHomeQueryService {

  /** 진행 중 미리보기 기본/최대 개수. */
  private static final int DEFAULT_IN_PROGRESS_LIMIT = 5;
  private static final int MAX_IN_PROGRESS_LIMIT = 20;

  /** 검수 대기 '신규' 판정 잠정 규칙 — 접수 후 이 시간 이내면 신규로 본다(리더 확정 시 조정). */
  private static final long NEW_WINDOW_HOURS = 24L;

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;

  public AdjusterHomeResponse getHome(UUID adjusterId, int inProgressLimit) {
    int limit = clampLimit(inProgressLimit);

    AdjusterIdentityRow identity = reportRepository.findAdjusterIdentity(adjusterId);
    Adjuster adjuster = toAdjuster(adjusterId, identity);

    long pendingCount = reportRepository.countPendingPool();
    long pendingNewCount =
        reportRepository.countPendingPoolNew(LocalDateTime.now().minusHours(NEW_WINDOW_HOURS));

    // 이번 달 완료만 월별 컬럼이 없어 실시간 집계(this month), 누적·상담은 adjuster_profiles 비정규화.
    long monthlyCompletedCount = countMonthlyCompleted(adjusterId);
    long inProgressCount = reportReviewRepository.countInProgressByAdjusterId(adjusterId);
    InProgressCases inProgressCases = resolveInProgressCases(adjusterId, inProgressCount, limit);

    Summary summary = new Summary(
        pendingCount,
        pendingNewCount,
        inProgressCount,
        monthlyCompletedCount,
        nullSafe(identity == null ? null : identity.getCasesReviewed()),
        nullSafe(identity == null ? null : identity.getCompletedConsultCount()),
        toRating(identity));

    return new AdjusterHomeResponse(adjuster, summary, inProgressCases);
  }

  /** 이번 달(현재 월) 검수 완료 실시간 집계 — adjuster_profiles에는 월별 컬럼이 없어 report_reviews로 센다. */
  private long countMonthlyCompleted(UUID adjusterId) {
    YearMonth thisMonth = YearMonth.now();
    LocalDateTime monthFrom = thisMonth.atDay(1).atStartOfDay();
    LocalDateTime monthTo = thisMonth.plusMonths(1).atDay(1).atStartOfDay();
    return reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, monthFrom, monthTo);
  }

  private long nullSafe(Integer value) {
    return value == null ? 0L : value;
  }

  private Adjuster toAdjuster(UUID adjusterId, AdjusterIdentityRow identity) {
    String name = identity == null ? null : identity.getName();
    String avatarUrl = identity == null ? null : identity.getAvatarUrl();
    return new Adjuster(adjusterId, name, avatarUrl);
  }

  /**
   * 평점은 adjuster_profiles 비정규화 컬럼(rating_mean·review_count)에서 읽는다.
   * 집계(후기 POST 시 갱신) 미구현이라 아직 채워지지 않았으면 average=null, reviewCount=0으로 내린다.
   */
  private Rating toRating(AdjusterIdentityRow identity) {
    if (identity == null) {
      return new Rating(null, 0L);
    }
    Double average = identity.getRatingMean() == null ? null : identity.getRatingMean().doubleValue();
    return new Rating(average, nullSafe(identity.getReviewCount()));
  }

  private InProgressCases resolveInProgressCases(UUID adjusterId, long total, int limit) {
    List<InProgressCaseRow> rows =
        reportReviewRepository.findInProgressCases(adjusterId, PageRequest.of(0, limit));
    List<InProgressCases.Item> items = rows.stream().map(this::toInProgressItem).toList();
    return new InProgressCases(total, items);
  }

  private InProgressCases.Item toInProgressItem(InProgressCaseRow row) {
    return new InProgressCases.Item(
        row.getReportId(),
        row.getCaseNo(),
        row.getAccidentType(),
        row.getTitle(),
        row.getReportStatus(),
        row.getReviewStatus(),
        stageLabel(row.getReportStatus(), row.getReviewStatus()),
        progressPercent(row.getReviewStatus()));
  }

  /**
   * (reportStatus, reviewStatus)에서 진행 단계 라벨 파생(잠정). COUNSELING은 상담 중,
   * SENT는 리포트가 채택 대기면 고객 검토 대기, 그 외 검수 중으로 본다.
   */
  private String stageLabel(String reportStatus, String reviewStatus) {
    if ("COUNSELING".equals(reviewStatus)) {
      return "상담 중";
    }
    if ("AWAITING_ADOPTION".equals(reportStatus)) {
      return "고객 검토 대기";
    }
    return "검수 중";
  }

  /** 진행률(잠정) — SENT 70, COUNSELING 90. 나머지는 진행 중 목록에 오지 않는다. */
  private int progressPercent(String reviewStatus) {
    if ("COUNSELING".equals(reviewStatus)) {
      return 90;
    }
    return 70;
  }

  private int clampLimit(int inProgressLimit) {
    if (inProgressLimit < 1) {
      return DEFAULT_IN_PROGRESS_LIMIT;
    }
    return Math.min(inProgressLimit, MAX_IN_PROGRESS_LIMIT);
  }
}
