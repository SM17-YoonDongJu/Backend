package com.soma.backend.domain.report.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.Hospitalization;
import com.soma.backend.domain.report.repository.ReviewContextRow;

/**
 * 검수 화면(편집 워크스페이스) 집계 응답. 사정사 작업본(REPORT_REVIEWS)이 없으면 AI 초안을, 있으면 작업본을 노출한다.
 *
 * <p>규칙: 보장·특약·근거는 작업본 유무로 전환(resolved), 쟁점은 AI 원본(REPORT_ISSUES)에 사정사 오버레이
 * (REPORT_ISSUES_REVIEWS)를 병합, 예상 보상은 AI 추정과 사정사 확정을 함께 담는다. 필드는 camelCase로 두고
 * snake_case 직렬화는 Jackson 전역 설정이 처리한다. region은 프론트 계약에 맞춰 단일 문자열로 내리고,
 * 리스트 필드는 null 대신 빈 배열로 직렬화한다.
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
    ClaimContext claim,
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
      Report report, ReviewContextRow context, List<String> region, ClaimDetails claimDetails,
      List<ReportIssue> aiIssues, ReportReview review, List<ReportAttachment> attachments) {
    boolean started = review != null;
    String regionText = ReportResponseSupport.joinRegion(region);
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
        regionText,
        report.getStatus() == null ? null : report.getStatus().name(),
        normalizeConfidence(report.getConfidenceLevel()),
        Boolean.TRUE.equals(report.getIsMasked()),
        report.getOfferedAmount(),
        Client.from(context, regionText),
        ClaimContext.from(context, claimDetails),
        attachmentItems,
        aiEstimate,
        adjusterEstimate,
        ReportResponseSupport.nullSafe(guarantees),
        ReportResponseSupport.nullSafe(special),
        ReportResponseSupport.nullSafe(basis),
        issues,
        started ? review.getReview() : null,
        started ? review.getStatus().name() : null,
        started,
        progressOf(issues));
  }

  /** confidence_level 원본(FastAPI 산출)을 프론트 enum(LOW/MEDIUM/HIGH) 계약에 맞춰 대문자로 정규화한다. */
  private static String normalizeConfidence(String confidenceLevel) {
    return confidenceLevel == null ? null : confidenceLevel.toUpperCase(Locale.ROOT);
  }

  /** AI 쟁점(REPORT_ISSUES) 원본에 사정사 검수(REPORT_ISSUES_REVIEWS) 오버레이를 병합하고 ADDED 신규 쟁점을 덧붙인다. */
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

  /** 입원 이력(구조)을 프론트 표기용 단일 문자열로 요약한다("입원 N일", 여러 건은 ", "로 연결, 없으면 ""). */
  private static String formatHospitalization(List<Hospitalization> hospitalizations) {
    if (hospitalizations == null || hospitalizations.isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (Hospitalization item : hospitalizations) {
      if (item.hospitalStart() == null || item.hospitalEnd() == null) {
        continue;
      }
      long days = ChronoUnit.DAYS.between(item.hospitalStart(), item.hospitalEnd()) + 1;
      parts.add("입원 " + days + "일");
    }
    return String.join(", ", parts);
  }

  /** ① 의뢰인 정보(users). region은 단일 문자열. */
  public record Client(
      String nickname, String gender, LocalDate birthDate, String region, LocalDateTime joinedAt) {

    public static Client from(ReviewContextRow context, String region) {
      if (context == null) {
        return null;
      }
      return new Client(
          context.getNickname(), context.getGender(), context.getBirthDate(),
          region, context.getJoinedAt());
    }
  }

  /**
   * ②③ 사고·청구·가입 보험 맥락. 진단(diagnosis)·입원(hospitalization)은 user_claims.details
   * (sealed {@link ClaimDetails})에서 읽어 프론트 표기용 단일 문자열로 단순화한다(진단은 · 대신 ", " 연결,
   * 입원은 "입원 N일"). 나머지 사고 정보·상품/보험사는 조회 프로젝션에서 온다.
   * 도메인 엔티티 {@code UserClaim}과 구분하기 위해 ClaimContext로 둔다(뷰 조각).
   */
  public record ClaimContext(
      String accidentType,
      LocalDate accidentDate,
      String diagnosis,
      String hospitalization,
      String description,
      String additionalInformation,
      String productName,
      String insurerName) {

    public static ClaimContext from(ReviewContextRow context, ClaimDetails details) {
      if (context == null) {
        return null;
      }
      return new ClaimContext(
          context.getClaimAccidentType(), context.getAccidentDate(),
          ReportResponseSupport.joinText(details == null ? null : details.diagnosis(), ", "),
          formatHospitalization(details == null ? null : details.hospitalizations()),
          context.getClaimDescription(), context.getAdditionalInformation(),
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
   * ⑥ 쟁점 1건 — AI 원본(REPORT_ISSUES)과 사정사 오버레이(REPORT_ISSUES_REVIEWS) 병합. reviewStatus가 null이면
   * 미검수 AI 쟁점, issueId가 null이면 사정사 신규(ADDED) 쟁점이다. impactAmount는 AI 추정 영향 금액,
   * modifiedImpactAmount는 사정사가 직접 입력한 영향 금액(미입력·미검수면 null)이다.
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
      Long modifiedImpactAmount,
      String modifiedReason,
      String excludedReason) {

    static IssueItem of(ReportIssue ai, ReportReviewIssue overlay) {
      return new IssueItem(
          ai.getId(),
          overlay == null ? null : overlay.getId(),
          ai.getTitle(),
          ai.getDescription(),
          ai.getAiStatus(),
          ReportResponseSupport.nullSafe(ai.getTags()),
          ai.getImpactAmount(),
          overlay == null ? null : overlay.getReviewStatus().name(),
          overlay == null ? null : overlay.getAdjusterOpinion(),
          overlay == null ? null : overlay.getTitle(),
          overlay == null ? null : overlay.getDescription(),
          overlay == null ? null : overlay.getImpactAmount(),
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
          List.of(),
          null,
          added.getReviewStatus().name(),
          added.getAdjusterOpinion(),
          added.getTitle(),
          added.getDescription(),
          added.getImpactAmount(),
          added.getModifiedReason(),
          added.getExcludedReason());
    }
  }

  /** 우측 검수 진행 요약(인정/수정/제외 카운트, total = 전체 쟁점 수). */
  public record Progress(int total, int accepted, int modified, int excluded) {
  }
}
