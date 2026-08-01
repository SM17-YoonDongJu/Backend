package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ADJUSTER_REVIEWS — 고객→사정사 사건 평점·후기. {@code reportId}로 평가 대상 사건에 연결한다(V26).
 *
 * <p>평가 등록(POST /adjusters/{id}/reviews) 쓰기와, GET /adjusters/me/reviewed-reports·평가 목록 조회
 * 읽기에 함께 쓴다. (user_id, adjuster_id) 조합당 1건이며 중복 방지는 서비스가 검사한다.
 */
@Entity
@Table(name = "adjuster_reviews")
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterReview {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Column(name = "report_id")
  private UUID reportId;

  @Column(name = "score")
  private Integer score;

  @Column(name = "review")
  private String review;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  /** 평가 등록 팩터리. reportId는 평가 자격(검수 완료)에 해당하는 사건과 연결한다(없으면 null). */
  public static AdjusterReview create(
      UUID userId, UUID adjusterId, @Nullable UUID reportId, Integer score, @Nullable String review) {
    AdjusterReview adjusterReview = new AdjusterReview();
    adjusterReview.userId = userId;
    adjusterReview.adjusterId = adjusterId;
    adjusterReview.reportId = reportId;
    adjusterReview.score = score;
    adjusterReview.review = review;
    return adjusterReview;
  }
}
