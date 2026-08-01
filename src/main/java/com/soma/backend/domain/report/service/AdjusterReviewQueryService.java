package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.AdjusterReviewListResponse;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.common.PageRequests;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 사정사 평가 목록 조회 유스케이스(GET /adjusters/{adjusterId}/reviews). page는 1-based(기본 1). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterReviewQueryService {

  private final AdjusterReviewRepository adjusterReviewRepository;
  private final UserRepository userRepository;

  public AdjusterReviewListResponse getReviews(UUID adjusterId, int page, int size) {
    if (!userRepository.existsById(adjusterId)) {
      throw new BusinessException(ErrorCode.ADJUSTER_NOT_FOUND);
    }
    Pageable pageable = PageRequests.ofOneBased(page, size);
    Page<AdjusterReviewRow> rows = adjusterReviewRepository.findReviewRows(adjusterId, pageable);
    return AdjusterReviewListResponse.from(rows);
  }
}
