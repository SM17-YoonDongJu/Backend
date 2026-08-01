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
}
