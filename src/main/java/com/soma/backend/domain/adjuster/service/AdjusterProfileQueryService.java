package com.soma.backend.domain.adjuster.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterMyProfileResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 손해사정사 본인 프로필 조회 유스케이스(GET /adjusters/me/profile).
 *
 * <p>검수 대기 수(pendingReviewCount)는 검수 대기 풀 전역 집계({@code reportRepository.countPending})를
 * 재사용한다 — {@code /reports/pending-review/summary}의 pending_count와 동일 값이라 중복 구현하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterProfileQueryService {

  private final AdjusterProfileRepository adjusterProfileRepository;
  private final ReportRepository reportRepository;

  public AdjusterMyProfileResponse getMyProfile(UUID userId) {
    AdjusterProfile profile = adjusterProfileRepository.findByUserId(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ADJUSTER_NOT_FOUND));
    long pendingReviewCount = reportRepository.countPending();
    return AdjusterMyProfileResponse.from(profile, pendingReviewCount);
  }
}
