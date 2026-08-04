package com.soma.backend.domain.user.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.user.dto.UserInsuranceListResponse;
import com.soma.backend.domain.user.repository.UserInsuranceRepository;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * 내 보험 정보 조회 유스케이스(조회 전용). 대상은 항상 요청 principal 본인이라 별도 인가 없이 userId로만
 * 조회한다(UserDashboardQueryService 관례). 보유 보험이 없으면 빈 목록을 반환한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserInsuranceQueryService {

  private final UserInsuranceRepository userInsuranceRepository;
  private final S3UploadService s3UploadService;

  public UserInsuranceListResponse getMyInsurances(UUID userId) {
    return UserInsuranceListResponse.from(
        userInsuranceRepository.findByUserIdOrderByCreatedAtDesc(userId),
        s3UploadService::presignedGetUrl);
  }
}
