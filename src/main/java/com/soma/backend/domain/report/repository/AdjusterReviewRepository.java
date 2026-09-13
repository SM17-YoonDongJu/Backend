package com.soma.backend.domain.report.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.AdjusterReview;

/** 사정사 평가(후기) 리포지토리 — 등록·중복 검사·목록 조회. */
public interface AdjusterReviewRepository extends JpaRepository<AdjusterReview, UUID> {

  /** (user, adjuster) 조합 중복 등록 방지용(glossary §13). */
  boolean existsByUserIdAndAdjusterId(UUID userId, UUID adjusterId);

  /**
   * 사정사별 평가 목록(최신순). 리뷰어 닉네임(USERS)·사건 유형(연결 REPORTS)을 조인한다.
   * report_id가 없는 평가는 사건 유형(accidentType)이 null이다(LEFT JOIN).
   */
  @Query(value = "SELECT new com.soma.backend.domain.report.repository.AdjusterReviewRow("
      + "u.nickname, ar.score, rp.accidentType, ar.createdAt, ar.review) "
      + "FROM AdjusterReview ar "
      + "JOIN User u ON u.id = ar.userId "
      + "LEFT JOIN Report rp ON rp.id = ar.reportId "
      + "WHERE ar.adjusterId = :adjusterId "
      + "ORDER BY ar.createdAt DESC",
      countQuery = "SELECT COUNT(ar) FROM AdjusterReview ar WHERE ar.adjusterId = :adjusterId")
  Page<AdjusterReviewRow> findReviewRows(@Param("adjusterId") UUID adjusterId, Pageable pageable);

  /**
   * 사정사 평점 비정규화 재집계(adjuster_profiles.rating_mean·review_count). 조건 고정·정렬 없음의 정적
   * 단건 집계라 QueryDSL이 아니라 JPQL로 쓴다(하네스 쿼리 규칙).
   *
   * <p>{@code score}가 null인 행은 평균·건수 양쪽에서 제외한다 — V1 DDL상 score가 nullable이라 과거 행에
   * null이 섞일 수 있는데, AVG는 null을 무시하고 COUNT(ar)는 세므로 걸러내지 않으면 평균과 건수의 모수가
   * 어긋난다. 평가가 없으면 {@code (null, 0)}이 돌아온다.
   *
   * <p>JPQL이라 Hibernate 자동 플러시(FlushMode.AUTO)가 걸려 같은 트랜잭션에서 방금 저장한 평가도 집계에
   * 포함된다(native였다면 놓친다).
   */
  @Query("SELECT new com.soma.backend.domain.report.repository.AdjusterRatingAggregate("
      + "AVG(ar.score), COUNT(ar)) "
      + "FROM AdjusterReview ar WHERE ar.adjusterId = :adjusterId AND ar.score IS NOT NULL")
  AdjusterRatingAggregate aggregateRating(@Param("adjusterId") UUID adjusterId);
}
