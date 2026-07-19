package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

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
 */
@Service
@RequiredArgsConstructor
public class AdjusterReviewCommandService {

  private final AdjusterReviewRepository adjusterReviewRepository;
  private final ReportReviewRepository reportReviewRepository;

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
    AdjusterReview review = adjusterReviewRepository.save(
        AdjusterReview.create(userId, adjusterId, eligibleReportIds.get(0), request.score(), request.content()));
    return CreateAdjusterReviewResponse.from(review);
  }
}
