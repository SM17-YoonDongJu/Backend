package com.soma.backend.domain.adjuster.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterMyPageResponse;
import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterHomeRepository;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 손해사정사 마이페이지/프로필 조회 유스케이스(GET /adjusters/me/mypage, GET /adjusters/me/profile).
 *
 * <p>두 API 모두 읽기 전용 크로스-애그리거트 뷰다 — AdjusterProfile(adjuster)·User(user)·
 * ReportReview·AdjusterReview(report) 애그리거트를 user_id로만 조립하고 어떤 상태도 바꾸지 않는다.
 * report_reviews.adjuster_id·adjuster_reviews.adjuster_id는 사정사의 user_id라 principal.userId를
 * 그대로 조회 키로 쓴다.
 *
 * <p>당월 검수완료·검수 대기 풀은 홈 대시보드와 동일 집계를 재사용한다
 * ({@code AdjusterHomeRepository.countCompletedBetween}·{@code countPendingPool}). 상담전환은
 * status IN (COUNSELING, ACCEPTED)('상담 도달') 기준으로 집계한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterProfileQueryService {

  /** flat 프로필 recent_reviews 노출 건수(최근 N건, created_at DESC). */
  private static final int RECENT_REVIEW_LIMIT = 2;

  private final AdjusterProfileRepository adjusterProfileRepository;
  private final UserRepository userRepository;
  private final AdjusterHomeRepository adjusterHomeRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final AdjusterReviewRepository adjusterReviewRepository;

  /** 마이페이지 4개 섹션(프로필·통계·당월 활동·자격) 집계 조회. */
  public AdjusterMyPageResponse getMyPage(UUID userId) {
    AdjusterProfile profile = findProfile(userId);
    User user = findUser(userId);

    long totalCompleted = reportReviewRepository.countByAdjusterId(userId);
    long reachedConsult = reportReviewRepository.countReachedConsultationByAdjusterId(userId);
    int conversionRate = totalCompleted == 0
        ? 0
        : Math.toIntExact(Math.round(reachedConsult * 100.0 / totalCompleted));

    YearMonth thisMonth = YearMonth.now();
    LocalDateTime monthFrom = thisMonth.atDay(1).atStartOfDay();
    LocalDateTime monthTo = thisMonth.plusMonths(1).atDay(1).atStartOfDay();

    long monthlyCompleted = adjusterHomeRepository.countCompletedBetween(userId, monthFrom, monthTo);
    long monthlyConsultConverted =
        reportReviewRepository.countReachedConsultationByAdjusterIdBetween(userId, monthFrom, monthTo);

    return AdjusterMyPageResponse.from(
        profile, user, totalCompleted, conversionRate, monthlyCompleted, monthlyConsultConverted);
  }

  /** 프로필 수정 화면 초기값용 flat 프로필 조회(누적 통계 + 최근 후기 2건). */
  public AdjusterProfileResponse getProfile(UUID userId) {
    AdjusterProfile profile = findProfile(userId);
    User user = findUser(userId);

    List<AdjusterReviewRow> recentReviews = adjusterReviewRepository
        .findReviewRows(userId, PageRequest.of(0, RECENT_REVIEW_LIMIT))
        .getContent();
    long handledCaseCount = reportReviewRepository.countByAdjusterId(userId);
    // 검수 대기 건수(파트너 헤더 "검수 대기 N건", BE #122) — 홈 대시보드 summary.pending_count와 동일 전역
    // 집계(countPendingPool: AWAITING_INSPECTION + AWAITING_ADOPTION).
    int pendingReviewCount = Math.toIntExact(adjusterHomeRepository.countPendingPool());

    return AdjusterProfileResponse.from(profile, user, recentReviews, handledCaseCount, pendingReviewCount);
  }

  private AdjusterProfile findProfile(UUID userId) {
    return adjusterProfileRepository.findByUserId(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ADJUSTER_NOT_FOUND));
  }

  private User findUser(UUID userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
  }
}
