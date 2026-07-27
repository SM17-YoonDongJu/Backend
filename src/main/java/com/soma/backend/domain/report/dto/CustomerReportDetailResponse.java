package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;

/**
 * GET /reports/{reportId} — 고객 리포트 상세 응답. 소유자(USER)·사정사 공용 shape이며, 파트너 앱의
 * draft-preview 부분집합({@code claimedMinAmount}·{@code claimedMaxAmount}·{@code offeredAmount}·
 * {@code omittedSpecialContract}·{@code issue})도 이 shape에 포함된다(사정사 리치 검수 데이터는
 * GET /reports/{id}/review가 별도 제공).
 *
 * <p>필드는 camelCase로 두고 snake_case 직렬화는 Jackson 전역 설정이 처리한다. status는 고객 노출 매핑
 * (CLOSED→MATCHED). 확정 보장·특약·근거는 채택된 제안(ACCEPTED report_review)이 있으면 그 값을, 없으면
 * report 원본을 노출한다. 금액(claimed·offered·impact)은 엔티티 nullability를 그대로 노출하고 0으로 치환하지
 * 않는다. treatment는 텍스트라 null을 빈 문자열로 coalesce하고, 리스트 필드는 null 대신 빈 배열로 내린다.
 * confidenceLevel은 대문자로 정규화한다(부재 시 null).
 */
public record CustomerReportDetailResponse(
    UUID reportId,
    String status,
    String accidentType,
    String treatment,
    Long claimedMinAmount,
    Long claimedMaxAmount,
    Long offeredAmount,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<String> basisTermsPrecedents,
    List<IssueItem> issue,
    String question,
    UUID adjusterId,
    String confidenceLevel,
    String reportNo,
    String reviewComment,
    LocalDateTime reviewedAt,
    Adjuster adjuster) {

  /**
   * 조회 결과를 상세 응답으로 조립한다. {@code opinionByIssueId}는 채택 리뷰의 쟁점 오버레이
   * (report_issues_reviews)에서 뽑은 report_issue_id→adjuster_opinion 맵으로, 각 쟁점 opinion을
   * COALESCE(사정사 의견, AI 초안 description)으로 채우는 데 쓴다.
   */
  public static CustomerReportDetailResponse from(
      Report report, List<ReportIssue> issues, CustomerReportDetailRow row,
      Map<UUID, String> opinionByIssueId) {
    boolean accepted = row.acceptedReviewId() != null;
    List<String> guarantees = accepted ? row.reviewGuarantees() : report.getApplicableGuarantees();
    List<String> special = accepted ? row.reviewSpecial() : report.getOmittedSpecialContract();
    List<String> basis = accepted ? row.reviewBasis() : report.getBasisTermsPrecedents();
    List<IssueItem> issueItems = issues.stream()
        .map(issue -> IssueItem.of(issue, opinionByIssueId.get(issue.getId())))
        .toList();
    Adjuster adjuster = report.getAdjusterId() == null
        ? null
        : new Adjuster(row.adjusterNickname(), row.adjusterCareer());
    return new CustomerReportDetailResponse(
        report.getId(),
        ReportResponseSupport.customerStatus(report.getStatus()),
        report.getAccidentType() == null ? null : report.getAccidentType().getValue(),
        report.getTreatment() == null ? "" : report.getTreatment(),
        report.getClaimedMinAmount(),
        report.getClaimedMaxAmount(),
        report.getOfferedAmount(),
        ReportResponseSupport.nullSafe(guarantees),
        ReportResponseSupport.nullSafe(special),
        ReportResponseSupport.nullSafe(basis),
        issueItems,
        report.getQuestion(),
        report.getAdjusterId(),
        normalizeConfidence(report.getConfidenceLevel()),
        report.getCaseNo(),
        accepted ? row.reviewComment() : null,
        accepted ? row.reviewedAt() : null,
        adjuster);
  }

  /** confidence_level을 대문자로 정규화한다(LOW|MEDIUM|HIGH). null이면 null 유지. */
  private static String normalizeConfidence(String value) {
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  /**
   * 쟁점 1건(report_issues). {@code opinion}은 사정사 의견(채택 리뷰 오버레이 adjuster_opinion) 우선,
   * 없으면 AI 초안 설명(description) 폴백이다. {@code status}는 AI 판정값(CONFIRMED|TRUSTED|INFO). 고객
   * 상세용 부분집합이라 issue_id는 노출하지 않는다.
   */
  public record IssueItem(
      String title,
      String opinion,
      String status,
      List<String> tags,
      Long impactAmount) {

    static IssueItem of(ReportIssue issue, String adjusterOpinion) {
      String opinion = adjusterOpinion != null ? adjusterOpinion : issue.getDescription();
      return new IssueItem(
          issue.getTitle(),
          opinion,
          issue.getAiStatus(),
          ReportResponseSupport.nullSafe(issue.getTags()),
          issue.getImpactAmount());
    }
  }

  /** 담당 사정사 요약(users.nickname·adjuster_profiles.career 연차). adjuster_id가 없으면 상위 필드가 null이다. */
  public record Adjuster(String nickname, Integer career) {
  }
}
