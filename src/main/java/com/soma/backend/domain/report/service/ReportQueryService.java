package com.soma.backend.domain.report.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 조회 유스케이스(design.md §6) — 목록·상세. CQRS 조회 전용. 소유자(userId=principal) 스코프로
 * 본인 리포트를 전 상태(AWAITING_INSPECTION 포함) 반환한다. 상세 조회는 소유자(USER) 또는 사정사만 허용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

  /** 페이지 크기 상한 — page size<=0(PageRequest.of가 IllegalArgumentException)·과대 page size(성능/메모리) 방어. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;

  /** userId 소유 리포트 카드 목록. status는 옵션 필터(REPORTS.status), page는 1-based. */
  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {
    ReportStatus statusFilter = parseStatus(status);
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, statusFilter, pageable);
    return ReportCardListResponse.from(rows, analyses(rows));
  }

  /**
   * 받은 제안 목록(GET /me/received-proposals) — 제안 받은(REJECTED 제외) 리뷰를 per-review 카드로 반환한다.
   * 응답 shape는 GET /reports와 동일이며 page는 1-based다.
   */
  public ReportCardListResponse getReceivedProposals(UUID userId, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findReportsWithProposals(userId, pageable);
    return ReportCardListResponse.from(rows, analyses(rows));
  }

  /**
   * 고객 리포트 상세(GET /reports/{reportId}). 인가는 리포트 소유자(USER, report.userId=principal) 또는
   * 사정사(CERTIFICATED/UNCERTIFICATED_ADJUSTER)만 허용하고, 둘 다 아니면 403 FORBIDDEN을 던진다.
   * 존재하지 않는 리포트는 404 REPORT_NOT_FOUND. 확정 배열·검수 코멘트·담당 사정사는 크로스-애그리거트
   * 읽기 모델로, 쟁점은 report_issues에서 조립한다.
   */
  public CustomerReportDetailResponse getReportDetail(UUID userId, String role, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId) && !isAdjuster(role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    List<ReportIssue> issues = reportIssueRepository.findAllByReportId(reportId);
    CustomerReportDetailRow row = reportRepository.findCustomerReportDetail(reportId);
    Map<UUID, String> opinions = loadAdjusterOpinions(row.acceptedReviewId());
    ReportAnalysis analysis = analysesOrDegrade(List.of(reportId)).get(reportId);
    return CustomerReportDetailResponse.from(report, issues, row, opinions, analysis);
  }

  /** 카드 페이지에 붙일 분석 상태를 리포트 id 배치 조회 1회로 만든다(카드 쿼리 조인 금지 — 행 복제). */
  private Map<UUID, ReportAnalysis> analyses(Page<ReportCardRow> rows) {
    return analysesOrDegrade(rows.getContent().stream().map(ReportCardRow::reportId).toList());
  }

  /**
   * 분석 상태 배치 조회 + degrade. {@code ai.ocr_job_failures}는 AI 워커 소유라 GRANT 미적용·미배포 상태가
   * 있을 수 있는데, 목록·상세는 서비스의 핵심 화면이라 그 이유로 통째로 500이 되면 안 된다.
   *
   * <p>저널(확정 실패) 조회 실패는 {@code resolveAll} 안에서 이미 리포트 단위로 흡수돼, {@code BLOCKED}·
   * {@code NEEDS_REUPLOAD}처럼 {@code reports.status}만으로 판정되는 상태는 살아남는다. 여기서 잡는
   * {@code DataAccessException}은 {@code resolveAll} 자체가 실패하는(리포트 배치 조회 실패 등) 더 드문
   * 경우를 위한 마지막 방어선이라, 이때는 분석 필드 전부를 PROCESSING으로 낮춰 내린다(가용성 우선).
   *
   * <p>{@code resolveAll}이 별도 트랜잭션(REQUIRES_NEW)이라 여기서 예외를 잡아도 이 조회 트랜잭션은
   * rollback-only로 오염되지 않는다. 로그에는 내부 식별자(s3_key·user_ref)를 남기지 않는다(design.md §12 S4).
   */
  private Map<UUID, ReportAnalysis> analysesOrDegrade(Collection<UUID> reportIds) {
    try {
      return reportAnalysisStatusQueryService.resolveAll(reportIds);
    } catch (DataAccessException ex) {
      log.warn("분석 처리 상태 조회 실패 — PROCESSING으로 degrade한다(대상 {}건). cause={}",
          reportIds.size(), ex.getMostSpecificCause().getMessage());
      return Map.of();
    }
  }

  /**
   * 채택 리뷰(ACCEPTED report_review)의 쟁점 오버레이(report_issues_reviews)에서 report_issue_id→
   * adjuster_opinion 맵을 만든다. 채택 리뷰가 없거나 사정사 의견이 없으면 빈 맵 — 상세는 AI description으로 폴백.
   */
  private Map<UUID, String> loadAdjusterOpinions(UUID acceptedReviewId) {
    if (acceptedReviewId == null) {
      return Map.of();
    }
    ReportReview review = reportReviewRepository.findById(acceptedReviewId).orElse(null);
    if (review == null) {
      return Map.of();
    }
    Map<UUID, String> opinions = new HashMap<>();
    for (ReportReviewIssue overlay : review.getIssues()) {
      if (overlay.getReportIssueId() != null && overlay.getAdjusterOpinion() != null) {
        opinions.put(overlay.getReportIssueId(), overlay.getAdjusterOpinion());
      }
    }
    return opinions;
  }

  /** 사정사 역할(자격 유무 무관)이면 임의 리포트 상세 조회를 허용한다(파트너 draft-preview 부분집합 소비). */
  private boolean isAdjuster(String role) {
    return "CERTIFICATED_ADJUSTER".equals(role) || "UNCERTIFICATED_ADJUSTER".equals(role);
  }

  /** size 하한 1(PageRequest.of 예외 방지)·상한 MAX_PAGE_SIZE로 클램프. page 클램프와 동일한 방어 톤. */
  private int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  /** status 파라미터를 enum으로 파싱한다. 빈 값이면 필터 없음(null), 알 수 없는 값이면 400 VALIDATION_ERROR. */
  private ReportStatus parseStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    try {
      return ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
