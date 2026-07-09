package com.soma.backend.domain.report.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.report.entity.claim.Hospitalization;

/**
 * GET /reports/{reportId} 상세 응답(design.md §6). Report(AI 초안)·UserClaim(사건 입력)·ReportIssue(쟁점)·
 * ReportAttachment(첨부)·조인 프로젝션(의뢰인/사정사/보험)을 서비스에서 조립한다.
 */
public record ReportDetailResponse(
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
    String confidenceLevel,
    List<Issue> issue,
    String question,
    String caseId,
    LocalDate accidentDate,
    String insuranceName,
    List<String> diagnosis,
    List<Hospitalization> hospitalizations,
    String description,
    Client client,
    Boolean isMasked,
    List<Attachment> attachments,
    UUID adjusterId,
    String adjusterNickname) {

  /** AI 쟁점 초안(REPORT_ISSUES). opinion=description, status=ai_status. */
  public record Issue(
      UUID issueId, String title, String opinion, String status, List<String> tags, Long impactAmount) {
  }

  /** 의뢰인(USERS) 마스킹 파생. is_masked=true면 maskedName은 마스킹된 표시명. */
  public record Client(String maskedName, String gender, String region) {
  }

  /** 첨부 서류(REPORT_ATTACHMENTS). fileType=mime_type. */
  public record Attachment(
      UUID id,
      String name,
      String fileType,
      Integer pageCount,
      String url,
      String issuedBy,
      LocalDate issuedAt,
      String aiSummary) {
  }
}
