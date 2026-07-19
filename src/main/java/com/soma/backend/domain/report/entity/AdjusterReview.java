package com.soma.backend.domain.report.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ADJUSTER_REVIEWS 읽기용 매핑(고객→사정사 사건 평점). {@code reportId}로 사건에 연결된다(V16).
 * 현재는 조회 전용 — GET /adjusters/me/reviewed-reports의 사건별 rating 조인에 쓴다. 평가 수집(쓰기)
 * 경로는 별도 티켓이므로 여기서는 조인에 필요한 컬럼만 매핑한다.
 */
@Entity
@Table(name = "adjuster_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterReview {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Column(name = "report_id")
  private UUID reportId;

  @Column(name = "score")
  private Integer score;
}
