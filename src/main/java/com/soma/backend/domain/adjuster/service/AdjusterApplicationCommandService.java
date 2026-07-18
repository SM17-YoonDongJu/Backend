package com.soma.backend.domain.adjuster.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationRequest;
import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.entity.Affiliation;
import com.soma.backend.domain.adjuster.entity.ApplicationStatus;
import com.soma.backend.domain.adjuster.repository.AdjusterApplicationRepository;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 손해사정사 자격 신청 접수 유스케이스. 신청 저장·문서 생성·역할 전이를 한 트랜잭션에서 처리한다. */
@Service
@RequiredArgsConstructor
public class AdjusterApplicationCommandService {

  private final AdjusterApplicationRepository adjusterApplicationRepository;
  private final UserRepository userRepository;

  /**
   * 자격 신청 접수. 자격증 번호·파일이 모두 없으면 {@code MISSING_REQUIRED_FIELD}(400), 진행 중(PENDING)
   * 신청이 있으면 {@code DUPLICATE_RESOURCE}(409). 신청인의 역할을 USER→UNCERTIFICATED_ADJUSTER로
   * 전이하며(이미 사정사/관리자면 역할 전이에서 409), 신청서와 증빙 문서 2종을 함께 저장한다.
   */
  @Transactional
  public CreateAdjusterApplicationResponse apply(UUID userId, CreateAdjusterApplicationRequest request) {
    if (!request.hasLicenseProof()) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if (adjusterApplicationRepository.existsByUserIdAndStatus(userId, ApplicationStatus.PENDING)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    user.applyForAdjuster();

    AdjusterApplication application = AdjusterApplication.create(
        userId, request.name(), request.phone(), request.specialties(), request.licenseNo(),
        request.licenseImageUrl(), request.career(), request.introduction(),
        parseAffiliation(request.affiliation()), request.region(), request.registrationImageUrl());
    adjusterApplicationRepository.save(application);

    return CreateAdjusterApplicationResponse.from(application);
  }

  /** 소속 코드 문자열을 {@link Affiliation}으로 변환한다. 정의되지 않은 값이면 {@code VALIDATION_ERROR}. */
  private Affiliation parseAffiliation(String affiliation) {
    try {
      return Affiliation.valueOf(affiliation);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
