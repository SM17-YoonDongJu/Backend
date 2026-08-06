package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;

/**
 * 채팅방 공유 리포트 응답(design.md §4). 방 참여자에게 사정사 검수 결과(요약·추정·보장·특약·근거·쟁점)를
 * 사용자 노출용 단일 값으로 담는다. 쟁점(issues)은 서비스가 §5 규칙(overlay 우선 resolve, EXCLUDED·미검수 필터)으로
 * 병합한 목록을 그대로 받는다. 필드는 camelCase로 두고 snake_case 직렬화는 Jackson 전역 설정이 처리한다.
 */
public record SharedReportResponse(
    UUID chatRoomId,
    UUID reportId,
    UUID proposalId,
    String caseNo,
    String accidentType,
    String title,
    String reportStatus,
    String reviewStatus,
    LocalDateTime reportUpdatedAt,
    LocalDateTime submittedAt,
    String summary,
    Adjuster adjuster,
    Estimate estimate,
    Integer offeredAmount,
    List<Issue> issues,
    int issueCount,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<String> basisTermsPrecedents) {

  public static SharedReportResponse from(
      ChatRoom chatRoom, ReportReview review, Report report,
      List<Issue> issues, AdjusterProfile adjusterProfile) {
    return new SharedReportResponse(
        chatRoom.getId(),
        report.getId(),
        review.getId(),
        report.getCaseNo(),
        report.getAccidentType() == null ? null : report.getAccidentType().getValue(),
        report.getTitle(),
        report.getStatus() == null ? null : report.getStatus().name(),
        review.getStatus() == null ? null : review.getStatus().name(),
        report.getUpdatedAt(),
        review.getCreatedAt(),
        review.getReview(),
        Adjuster.from(review.getAdjusterId(), adjusterProfile),
        new Estimate(review.getEstimateMinAmount(), review.getEstimateMaxAmount()),
        report.getOfferedAmount(),
        issues,
        issues.size(),
        review.getApplicableGuarantees(),
        review.getOmittedSpecialContract(),
        review.getBasisTermsPrecedents());
  }

  /** ⑨ 헤더 + ⑧ 사정사 요약. 프로필(AdjusterProfile)이 없으면 name·career·specialties는 null(방어). */
  public record Adjuster(UUID adjusterId, String name, Integer career, List<String> specialties) {

    public static Adjuster from(UUID adjusterId, AdjusterProfile profile) {
      if (profile == null) {
        return new Adjuster(adjusterId, null, null, null);
      }
      return new Adjuster(adjusterId, profile.getName(), profile.getCareer(), profile.getSpecialties());
    }
  }

  /** 사정사 확정 보상 범위(최소·최대). */
  public record Estimate(Integer min, Integer max) {
  }

  /**
   * ③ 쟁점별 검수 의견 1건 — AI 원본(REPORT_ISSUES)과 사정사 오버레이(REPORT_ISSUES_REVIEWS)를 서비스가 §5로
   * resolve한 단일 값이다. issueId가 null이면 사정사 신규(ADDED) 쟁점이고, tags는 AI 원본(ADDED면 빈 배열)이다.
   */
  public record Issue(
      UUID issueId,
      UUID reviewIssueId,
      String title,
      String adjusterOpinion,
      String description,
      Integer impactAmount,
      String reviewStatus,
      List<String> tags) {
  }
}
