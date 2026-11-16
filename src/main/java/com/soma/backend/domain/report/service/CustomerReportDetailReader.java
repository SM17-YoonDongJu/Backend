package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 상세(GET /reports/{reportId})의 본 조회 — 리포트·인가·쟁점·상세 행·채택 검수 의견을 짧은 readOnly
 * 트랜잭션 하나에서 읽어 둔다. 분석 상태 판정({@link ReportAnalysisResolver#resolveAll})은 이 트랜잭션이 끝나 커넥션을
 * 반납한 뒤에 호출자({@link ReportQueryService})가 따로 한다.
 *
 * <p><b>왜 별도 Bean인가.</b> 상세 조립에는 트랜잭션이 필요하다. 채택 의견은 {@link ReportReview#adjusterOpinionsByIssue}가
 * 지연 로딩 컬렉션(issues)을 돌아야 나오고, {@code open-in-view: false}라 그동안 세션이 열려 있어야 한다. 그런데 조회
 * 서비스에는 트랜잭션을 둘 수 없고(#406, 이유는 {@link ReportQueryService} javadoc), 같은 클래스의 메서드에 붙이면 자가
 * 호출이라 프록시를 타지 않아 적용되지 않는다. 그래서 트랜잭션이 필요한 본 조회만 이 Bean으로 떼어냈다.
 *
 * <p><b>기본 전파(REQUIRED)다 — REQUIRES_NEW로 바꾸지 말 것.</b> 이 Bean은 실패를 격리하려는 게 아니라 트랜잭션 범위를
 * 좁히려는 것이다. 운영 호출자는 바깥 트랜잭션이 없어 둘의 차이가 없지만, REQUIRES_NEW로 바꾸면 테스트 트랜잭션 안에서 상세를
 * 부르는 테스트(예: {@code CustomerReportDetailExecutionTest})가 새 커넥션에서 미커밋 시드를 못 봐 전부 404로 깨진다.
 *
 * <p><b>인가를 여기서 한다.</b> 존재 확인(404)이 인가(403)보다 먼저고, 거부되면 쟁점·상세 행·검수를 읽지 않는다. 규칙
 * 자체는 {@link Report#isViewableBy} 한 곳에 있고(분석 근거 수치 API와 공유) 여기서는 부르기만 한다.
 *
 * <p>돌려주는 {@link Snapshot}은 트랜잭션 밖에서 쓰인다. 그래서 지연 로딩 프록시나 컬렉션을 담지 않고, 채택 의견은
 * 맵으로 풀어서 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class CustomerReportDetailReader {

  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewRepository reportReviewRepository;

  /**
   * 상세 조립 재료를 트랜잭션 하나에서 읽는다.
   *
   * @throws BusinessException 리포트가 없으면 {@code REPORT_NOT_FOUND}(404), 열람 권한이 없으면 {@code FORBIDDEN}(403).
   *                           존재 확인이 먼저다
   */
  @Transactional(readOnly = true)
  public Snapshot read(UUID viewerId, String viewerRole, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    // 인가 — 규칙은 Report.isViewableBy 한 곳에 있다(분석 근거 수치 API와 공유).
    if (!report.isViewableBy(viewerId, viewerRole)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    List<ReportIssue> issues = reportIssueRepository.findAllByReportId(reportId);
    CustomerReportDetailRow row = reportRepository.findCustomerReportDetail(reportId);
    return new Snapshot(report, issues, row, adjusterOpinions(row.acceptedReviewId()));
  }

  /**
   * 채택 리뷰(ACCEPTED report_review)의 쟁점 오버레이(report_issues_reviews)에서 report_issue_id→adjuster_opinion 맵을
   * 만든다. 채택 리뷰가 없거나 사정사 의견이 없으면 빈 맵이고, 상세는 AI description으로 대신한다. 지연 로딩(issues)이 이
   * 트랜잭션 안에서 끝나야 하므로 맵으로 풀어서 돌려준다.
   */
  private Map<UUID, String> adjusterOpinions(UUID acceptedReviewId) {
    if (acceptedReviewId == null) {
      return Map.of();
    }
    return reportReviewRepository.findById(acceptedReviewId)
        .map(ReportReview::adjusterOpinionsByIssue)
        .orElse(Map.of());
  }

  /**
   * 트랜잭션 안에서 다 읽어 둔 상세 조립 재료. {@code report}·{@code issues}는 연관 매핑 없이 기본 속성만 가진
   * 엔티티라, 트랜잭션이 끝나 분리된 뒤에도 그대로 읽힌다.
   *
   * @param adjusterOpinionByIssueId report_issue_id → 채택 검수의 사정사 의견. 채택 검수나 의견이 없으면 빈 맵
   */
  public record Snapshot(
      Report report,
      List<ReportIssue> issues,
      CustomerReportDetailRow row,
      Map<UUID, String> adjusterOpinionByIssueId) {
  }
}
