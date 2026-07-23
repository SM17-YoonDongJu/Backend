package com.soma.backend.domain.user.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 고객 홈 BFF 응답(GET /users/me/dashboard). 상태 기반 섹션 스왑을 위해 온보딩 분기(report_count)·대표
 * 활성 리포트·제안가 집계·할 일 카운트·대표 안읽음 채팅을 한 번에 조합한다. 전부 기존 ERD 필드의 조합/집계이며
 * 신규 컬럼은 없다(파트너 홈 GET /adjusters/me/home 대칭). {@code activeReport}·{@code proposalSummary}·
 * {@code unreadChat}은 해당 없음 시 null이고, {@code reportCount} 0이면 프론트가 온보딩을 렌더한다.
 * 필드는 Jackson 전역 설정에 따라 snake_case로 직렬화된다(report_count·active_report 등).
 */
public record UserDashboardResponse(
    long reportCount,
    ActiveReport activeReport,
    ProposalSummary proposalSummary,
    Todos todos,
    UnreadChat unreadChat) {

  /**
   * 대표 활성 리포트(CLOSED·NOT_SELECTED 제외 중 가장 최근 접수 1건). {@code firstReviewedAt}은 최초 검수
   * 시각(min REPORT_REVIEWS.created_at, 아직 없으면 null), {@code proposalCount}는 노출 제안(거절 제외) 수.
   */
  public record ActiveReport(
      UUID reportId,
      String title,
      String accidentType,
      String status,
      LocalDateTime createdAt,
      LocalDateTime firstReviewedAt,
      long proposalCount) {
  }

  /**
   * 대표 활성 리포트에 달린 제안(거절 제외) 견적 집계 + 항목 목록. {@code minAmount}·{@code maxAmount}·
   * {@code avgAmount}는 견적이 있는 제안이 하나도 없으면 null이다.
   */
  public record ProposalSummary(
      int count,
      Long minAmount,
      Long maxAmount,
      Long avgAmount,
      List<Item> items) {

    /** speciality(단수)는 사정사 specialties[] 첫 전문분야, career는 연차. nickname은 사정사 이름. */
    public record Item(
        UUID proposalId,
        UUID adjusterId,
        String nickname,
        Integer career,
        String speciality,
        Long estimateMinAmount,
        Long estimateMaxAmount) {
    }
  }

  /** 홈 상단 할 일 배지. 미확인(미결정, SENT) 제안 수와 검수 완료(채택 대기) 리포트 수. */
  public record Todos(
      long unconfirmedProposals,
      long reviewCompleted) {
  }

  /** 대표 안읽음 채팅 1건(안읽음 있는 방 중 최근 메시지순 top). 상대는 사정사다. */
  public record UnreadChat(
      UUID chatRoomId,
      UUID reportId,
      UUID adjusterId,
      String adjusterNickname,
      String adjusterAvatarUrl,
      String lastMessage,
      LocalDateTime lastMessageAt,
      long unreadCount) {
  }
}
