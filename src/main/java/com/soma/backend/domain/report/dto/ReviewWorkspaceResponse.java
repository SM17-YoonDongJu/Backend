package com.soma.backend.domain.report.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.repository.ReviewContextRow;

/**
 * 검수 화면(편집 워크스페이스) 집계 응답. 사정사 작업본(REPORT_REVIEWS)이 없으면 AI 초안을, 있으면 작업본을 노출한다.
 *
 * <p>규칙: 보장·특약·근거는 작업본 유무로 전환(resolved), 쟁점은 AI 원본(REPORT_ISSUES)에 사정사 오버레이
 * (REPORT_REVIEW_ISSUES)를 병합, 예상 보상은 AI 추정과 사정사 확정을 함께 담는다. 필드는 camelCase로 두고
 * snake_case 직렬화는 Jackson 전역 설정이 처리한다.
 */
public record ReviewWorkspaceResponse(
    UUID reportId,
    String caseNo,
    String title,
    String accidentType,
    String region,
    String status,
    String confidenceLevel,
    boolean isMasked,
    Long offeredAmount,
    Client client,
    Claim claim,
    List<AttachmentItem> attachments,
    Estimate aiEstimate,
    Estimate adjusterEstimate,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<String> basisTermsPrecedents,
    List<IssueItem> issues,
    String review,
    String reviewStatus,
    boolean started,
    Progress progress) {

  public static ReviewWorkspaceResponse from(
      Report report, ReviewContextRow context, List<ReportIssue> aiIssues,
      ReportReview review, List<ReportAttachment> attachments) {
    boolean started = review != null;
    List<IssueItem> issues = mergeIssues(aiIssues, review);
    List<AttachmentItem> attachmentItems = attachments.stream().map(AttachmentItem::from).toList();
    Estimate aiEstimate = new Estimate(report.getClaimedMinAmount(), report.getClaimedMaxAmount());
    Estimate adjusterEstimate =
        started ? new Estimate(review.getEstimateMinAmount(), review.getEstimateMaxAmount()) : null;
    List<String> guarantees = started ? review.getApplicableGuarantees() : report.getApplicableGuarantees();
    List<String> special = started ? review.getOmittedSpecialContract() : report.getOmittedSpecialContract();
    List<String> basis = started ? review.getBasisTermsPrecedents() : report.getBasisTermsPrecedents();
    return new ReviewWorkspaceResponse(
        report.getId(),
        report.getCaseNo(),
        report.getTitle(),
        report.getAccidentType() == null ? null : report.getAccidentType().getValue(),
        context == null ? null : context.getRegion(),
        report.getStatus() == null ? null : report.getStatus().name(),
        report.getConfidenceLevel(),
        Boolean.TRUE.equals(report.getIsMasked()),
        report.getOfferedAmount(),
        Client.from(context),
        Claim.from(context),
        attachmentItems,
        aiEstimate,
        adjusterEstimate,
        guarantees,
        special,
        basis,
        issues,
        started ? review.getReview() : null,
        started ? review.getStatus().name() : null,
        started,
        progressOf(issues));
  }

  /** AI 쟁점(REPORT_ISSUES) 원본에 사정사 검수(REPORT_REVIEW_ISSUES) 오버레이를 병합하고 ADDED 신규 쟁점을 덧붙인다. */
  private static List<IssueItem> mergeIssues(List<ReportIssue> aiIssues, ReportReview review) {
    Map<UUID, ReportReviewIssue> overlayByIssueId = new HashMap<>();
    List<ReportReviewIssue> added = new ArrayList<>();
    if (review != null) {
      for (ReportReviewIssue overlay : review.getIssues()) {
        if (overlay.getReportIssueId() == null) {
          added.add(overlay);
        } else {
          overlayByIssueId.put(overlay.getReportIssueId(), overlay);
        }
      }
    }
    List<IssueItem> items = new ArrayList<>();
    for (ReportIssue ai : aiIssues) {
      items.add(IssueItem.of(ai, overlayByIssueId.get(ai.getId())));
    }
    for (ReportReviewIssue addedIssue : added) {
      items.add(IssueItem.ofAdded(addedIssue));
    }
    return items;
  }

  private static Progress progressOf(List<IssueItem> issues) {
    int accepted = 0;
    int modified = 0;
    int excluded = 0;
    for (IssueItem item : issues) {
      if ("ACCEPTED".equals(item.reviewStatus())) {
        accepted++;
      } else if ("MODIFIED".equals(item.reviewStatus())) {
        modified++;
      } else if ("EXCLUDED".equals(item.reviewStatus())) {
        excluded++;
      }
    }
    return new Progress(issues.size(), accepted, modified, excluded);
  }

  /** ① 의뢰인 정보(users). */
  public record Client(
      String nickname, String gender, LocalDate birthDate, String region, LocalDateTime joinedAt) {

    public static Client from(ReviewContextRow context) {
      if (context == null) {
        return null;
      }
      return new Client(
          context.getNickname(), context.getGender(), context.getBirthDate(),
          context.getRegion(), context.getJoinedAt());
    }
  }

  /** ②③ 사고·청구·가입 보험(user_claims + insurance_products + insurers). */
  public record Claim(
      String accidentType,
      String diagnosis,
      LocalDate accidentDate,
      String hospitalization,
      String description,
      String additionalInformation,
      String productName,
      String insurerName) {

    public static Claim from(ReviewContextRow context) {
      if (context == null) {
        return null;
      }
      return new Claim(
          context.getClaimAccidentType(), context.getDiagnosis(), context.getAccidentDate(),
          context.getHospitalization(), context.getClaimDescription(), context.getAdditionalInformation(),
          context.getProductName(), context.getInsurerName());
    }
  }

  /** 보상 범위(최소·최대). */
  public record Estimate(Long min, Long max) {
  }

  /** ④ 첨부 서류 1건(REPORT_ATTACHMENTS — 리치). */
  public record AttachmentItem(
      UUID attachmentId,
      String name,
      String mimeType,
      String url,
      String reportType,
      Integer pageCount,
      String issuedBy,
      LocalDate issuedAt,
      String aiSummary) {

    public static AttachmentItem from(ReportAttachment attachment) {
      return new AttachmentItem(
          attachment.getId(),
          attachment.getName(),
          attachment.getMimeType(),
          attachment.getUrl(),
          attachment.getReportType(),
          attachment.getPageCount(),
          attachment.getIssuedBy(),
          attachment.getIssuedAt(),
          attachment.getAiSummary());
    }
  }

  /**
   * ⑥ 쟁점 1건 — AI 원본(REPORT_ISSUES)과 사정사 오버레이(REPORT_REVIEW_ISSUES) 병합. reviewStatus가 null이면
   * 미검수 AI 쟁점, issueId가 null이면 사정사 신규(ADDED) 쟁점이다.
   */
  public record IssueItem(
      UUID issueId,
      UUID reviewIssueId,
      String aiTitle,
      String aiDescription,
      String aiStatus,
      List<String> tags,
      Long impactAmount,
      String reviewStatus,
      String adjusterOpinion,
      String modifiedTitle,
      String modifiedDescription,
      String modifiedReason,
      String excludedReason) {

    static IssueItem of(ReportIssue ai, ReportReviewIssue overlay) {
      return new IssueItem(
          ai.getId(),
          overlay == null ? null : overlay.getId(),
          ai.getTitle(),
          ai.getDescription(),
          ai.getAiStatus(),
          ai.getTags(),
          ai.getImpactAmount(),
          overlay == null ? null : overlay.getReviewStatus().name(),
          overlay == null ? null : overlay.getAdjusterOpinion(),
          overlay == null ? null : overlay.getTitle(),
          overlay == null ? null : overlay.getDescription(),
          overlay == null ? null : overlay.getModifiedReason(),
          overlay == null ? null : overlay.getExcludedReason());
    }

    static IssueItem ofAdded(ReportReviewIssue added) {
      return new IssueItem(
          null,
          added.getId(),
          null,
          null,
          null,
          null,
          null,
          added.getReviewStatus().name(),
          added.getAdjusterOpinion(),
          added.getTitle(),
          added.getDescription(),
          added.getModifiedReason(),
          added.getExcludedReason());
    }
  }

  /** 우측 검수 진행 요약(인정/수정/제외 카운트, total = 전체 쟁점 수). */
  public record Progress(int total, int accepted, int modified, int excluded) {
  }
}
