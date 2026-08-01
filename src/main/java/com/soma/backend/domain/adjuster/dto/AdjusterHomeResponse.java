package com.soma.backend.domain.adjuster.dto;

import java.util.List;
import java.util.UUID;

/**
 * 사정사 홈 대시보드 집계 응답. 상단 요약 카드(summary) + 진행 중 사건 미리보기(inProgressCases).
 * 검수 대기 목록은 본 응답에 담지 않는다(프론트가 검수 대기 목록 API로 조회).
 */
public record AdjusterHomeResponse(Adjuster adjuster, Summary summary, InProgressCases inProgressCases) {

  /** 헤더 인사말·아바타용. name은 adjuster_profiles.name, 없으면 users.nickname. */
  public record Adjuster(UUID id, String name, String avatarUrl) {
  }

  /**
   * 요약 카드 수치. pendingCount·pendingNewCount는 전체 풀(global) 기준,
   * 나머지는 요청 사정사(me) 기준. rating은 평점 계산 미구현으로 현재 placeholder(0/0)다.
   */
  public record Summary(
      long pendingCount,
      long pendingNewCount,
      long inProgressCount,
      long monthlyCompletedCount,
      long totalCompletedCount,
      long consultationConvertedCount,
      Rating rating) {
  }

  /** 고객 평정. 평균(평점 계산 미구현 시 0.0)과 후기 수. 프론트 계약을 항상 number로 고정한다. */
  public record Rating(double average, long reviewCount) {
  }

  /** 진행 중 사건 미리보기(top N) + 전체 개수. */
  public record InProgressCases(long total, List<Item> items) {

    /** stageLabel·progressPercent는 (reportStatus, reviewStatus)에서 파생한 잠정 표기다. */
    public record Item(
        UUID reportId,
        String caseNo,
        String accidentType,
        String title,
        String reportStatus,
        String reviewStatus,
        String stageLabel,
        int progressPercent) {
    }
  }
}
