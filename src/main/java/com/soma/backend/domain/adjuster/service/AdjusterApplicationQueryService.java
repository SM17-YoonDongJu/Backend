package com.soma.backend.domain.adjuster.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.repository.AdjusterApplicationRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 내 자격 신청 상태 조회 유스케이스(본인 최신 신청). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterApplicationQueryService {

  private final AdjusterApplicationRepository adjusterApplicationRepository;

  /**
   * 본인의 최신 신청 1건을 조회한다. 신청 이력이 없으면 {@code POST_NOT_FOUND}
   * (프론트는 이 응답을 받으면 자격 신청 폼으로 분기한다).
   */
  public AdjusterApplicationResponse getMyApplication(UUID userId) {
    AdjusterApplication application = adjusterApplicationRepository
        .findTopByUserIdOrderByCreatedAtDesc(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
    return AdjusterApplicationResponse.from(application);
  }
}
