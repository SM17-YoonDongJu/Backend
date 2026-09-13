package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.service.AdjusterProfileStatsCommandService;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewRequest;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewResponse;
import com.soma.backend.domain.report.entity.AdjusterReview;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 사정사 평가 등록 유스케이스(POST /adjusters/{adjusterId}/reviews).
 *
 * <p>중복: (user, adjuster) 조합당 1건이라 이미 있으면 {@code DUPLICATE_RESOURCE}(409). 자격: 이 사정사가
 * 요청 사용자의 사건을 수임(ACCEPTED)한 이력이 있어야 하며(없으면 {@code FORBIDDEN} 403), 평가를 그 사건
 * (report_id)에 연결한다 — 이후 검수 내역의 사건별 rating 조인으로 노출된다. score 범위 검증은 요청 DTO(@Valid).
 *
 * <p>등록에 성공하면 같은 트랜잭션에서 대상 사정사 프로필의 평점 집계(rating_mean·review_count)를
 * 갱신한다 — 갱신은 adjuster 컨텍스트가 소유한 {@link AdjusterProfileStatsCommandService}에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class AdjusterReviewCommandService {

  private final AdjusterReviewRepository adjusterReviewRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final AdjusterProfileStatsCommandService adjusterProfileStatsCommandService;

  @Transactional
  public CreateAdjusterReviewResponse createReview(
      UUID userId, UUID adjusterId, CreateAdjusterReviewRequest request) {
    if (adjusterReviewRepository.existsByUserIdAndAdjusterId(userId, adjusterId)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    List<UUID> eligibleReportIds = reportReviewRepository.findAcceptedReportIdsForReviewer(adjusterId, userId);
    if (eligibleReportIds.isEmpty()) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    // 재집계가 방금 등록한 평가를 반드시 포함하도록 flush까지 마친 뒤 집계를 돌린다(JPQL 자동 플러시에 기대지 않음).
    AdjusterReview review = adjusterReviewRepository.saveAndFlush(
        AdjusterReview.create(userId, adjusterId, eligibleReportIds.get(0), request.score(), request.content()));
    adjusterProfileStatsCommandService.refreshRating(adjusterId);
    return CreateAdjusterReviewResponse.from(review);
  }
}
