package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;

/**
 * API#6 — 검수 대기 리포트의 AI 초안 상세 응답. 미리보기(⑤)와 초안 전체 보기(⑦)를 한 응답으로 커버한다.
 *
 * <p>필드는 camelCase로 두고 snake_case 직렬화는 Jackson 전역 설정이 처리한다.
 * accidentType은 목록(API#2)과 표기를 맞추기 위해 enum이 아니라 DB 원본 문자열(getValue)을 담는다.
 * 첨부는 검수 대기 화면용이라 REPORTS.documents({name:url} 비정규화 맵)에서 내린다 — ai_summary 등 상세 첨부는
 * 검수 화면 전용 API가 REPORT_ATTACHMENTS로 별도 제공한다. 확정 보상금액은 노출하지 않고 청구 범위·offerHeadroom만
 * 제공한다(금소법 §17).
 */
public record ReportDetailResponse(
    UUID reportId,
    String caseNo,
    String accidentType,
    List<String> region,
    boolean isMasked,
    Long claimedMinAmount,
    Long claimedMaxAmount,
    Long offeredAmount,
    long offerHeadroom,
    String treatment,
    String question,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<String> basisTermsPrecedents,
    List<IssueItem> issues,
    int issueCount,
    List<DocumentItem> documents,
    int documentCount,
    boolean held) {

  public static ReportDetailResponse from(
      Report report, List<String> region, boolean held, List<ReportIssue> issues) {
    List<IssueItem> issueItems = issues.stream().map(IssueItem::from).toList();
    List<DocumentItem> documentItems = toDocumentItems(report.getDocuments());
    return new ReportDetailResponse(
        report.getId(),
        report.getCaseNo(),
        report.getAccidentType() == null ? null : report.getAccidentType().getValue(),
        region,
        Boolean.TRUE.equals(report.getIsMasked()),
        report.getClaimedMinAmount(),
        report.getClaimedMaxAmount(),
        report.getOfferedAmount(),
        report.amountRange().offerHeadroom(),
        report.getTreatment(),
        report.getQuestion(),
        report.getApplicableGuarantees(),
        report.getOmittedSpecialContract(),
        report.getBasisTermsPrecedents(),
        issueItems,
        issueItems.size(),
        documentItems,
        documentItems.size(),
        held);
  }

  private static List<DocumentItem> toDocumentItems(Map<String, String> documents) {
    if (documents == null) {
      return List.of();
    }
    return documents.entrySet().stream()
        .map(entry -> new DocumentItem(entry.getKey(), entry.getValue()))
        .toList();
  }

  /** AI가 짚은 쟁점 1건. report_issues 한 행에 대응한다. */
  public record IssueItem(
      UUID issueId,
      String title,
      String description,
      String aiStatus,
      List<String> tags,
      Long impactAmount) {

    public static IssueItem from(ReportIssue issue) {
      return new IssueItem(
          issue.getId(),
          issue.getTitle(),
          issue.getDescription(),
          issue.getAiStatus(),
          issue.getTags(),
          issue.getImpactAmount());
    }
  }

  /** 첨부 서류 1건(검수 대기 화면용). REPORTS.documents 맵의 name→url 엔트리에 대응한다. */
  public record DocumentItem(String name, String url) {
  }
}
