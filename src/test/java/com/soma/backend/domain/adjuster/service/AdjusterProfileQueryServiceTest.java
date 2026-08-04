package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.adjuster.dto.AdjusterMyPageResponse;
import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterHomeRepository;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3UploadService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterProfileQueryService 단위 테스트")
class AdjusterProfileQueryServiceTest {

  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private AdjusterHomeRepository adjusterHomeRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private AdjusterReviewRepository adjusterReviewRepository;
  @Mock
  private S3UploadService s3UploadService;
  @InjectMocks
  private AdjusterProfileQueryService service;

  private final UUID userId = UUID.randomUUID();

  // ---------- getMyPage ----------

  @Test
  @DisplayName("getMyPage: nickname은 USERS.nickname(사정사 프로필 name 아님)이고 4개 섹션을 채워 반환한다")
  void getMyPage_success() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    given(profile.getReviewCount()).willReturn(12);
    given(profile.getRatingMean()).willReturn(new BigDecimal("4.90"));
    given(profile.getLicenseNo()).willReturn("제2026-0412호");
    given(profile.getActivityRegion()).willReturn(List.of("서울", "경기"));

    User user = mock(User.class);
    given(user.getNickname()).willReturn("김사정");
    given(user.getRole()).willReturn(Role.CERTIFICATED_ADJUSTER);
    given(user.getCreatedAt()).willReturn(LocalDateTime.of(2026, 1, 1, 0, 0));

    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(reportReviewRepository.countByAdjusterId(userId)).willReturn(10L);
    given(reportReviewRepository.countReachedConsultationByAdjusterId(userId)).willReturn(6L);
    given(adjusterHomeRepository.countCompletedBetween(eq(userId), any(), any())).willReturn(3L);
    given(reportReviewRepository.countReachedConsultationByAdjusterIdBetween(eq(userId), any(), any()))
        .willReturn(2L);

    AdjusterMyPageResponse response = service.getMyPage(userId);

    assertThat(response.profile().nickname()).isEqualTo("김사정");
    assertThat(response.profile().email()).isNull();
    assertThat(response.profile().role()).isEqualTo("CERTIFICATED_ADJUSTER");
    assertThat(response.profile().activityRegion()).isEqualTo("서울·경기");
    assertThat(response.stats().averageRating()).isEqualTo(4.9);
    assertThat(response.stats().reviewCount()).isEqualTo(12);
    assertThat(response.stats().totalCompletedCount()).isEqualTo(10L);
    // round(6 / 10 * 100) = 60
    assertThat(response.stats().consultationConversionRate()).isEqualTo(60);
    assertThat(response.monthlyActivity().completedCount()).isEqualTo(3L);
    assertThat(response.monthlyActivity().consultationConvertedCount()).isEqualTo(2L);
    assertThat(response.monthlyActivity().averageRating()).isEqualTo(4.9);
    assertThat(response.certification().licenseNo()).isEqualTo("제2026-0412호");
    assertThat(response.certification().activityRegion()).isEqualTo("서울·경기");
    assertThat(response.certification().createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
  }

  @Test
  @DisplayName("getMyPage: 신규 사정사(후기 0·검수 0)면 평점·전환율은 0, 카운트는 0")
  void getMyPage_newAdjuster_zeroRules() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    given(profile.getReviewCount()).willReturn(0);

    User user = mock(User.class);
    given(user.getRole()).willReturn(Role.UNCERTIFICATED_ADJUSTER);

    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(reportReviewRepository.countByAdjusterId(userId)).willReturn(0L);

    AdjusterMyPageResponse response = service.getMyPage(userId);

    assertThat(response.stats().averageRating()).isEqualTo(0.0);
    assertThat(response.stats().reviewCount()).isZero();
    assertThat(response.stats().totalCompletedCount()).isZero();
    assertThat(response.stats().consultationConversionRate()).isZero();
    assertThat(response.monthlyActivity().completedCount()).isZero();
    assertThat(response.monthlyActivity().consultationConvertedCount()).isZero();
    assertThat(response.monthlyActivity().averageRating()).isEqualTo(0.0);
    assertThat(response.profile().email()).isNull();
  }

  @Test
  @DisplayName("getMyPage: 전환율은 round(reached×100/total) 정수 — reached=1,total=3 → 33")
  void getMyPage_conversionRate_rounding() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    User user = mock(User.class);
    given(user.getRole()).willReturn(Role.CERTIFICATED_ADJUSTER);

    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(reportReviewRepository.countByAdjusterId(userId)).willReturn(3L);
    given(reportReviewRepository.countReachedConsultationByAdjusterId(userId)).willReturn(1L);

    AdjusterMyPageResponse response = service.getMyPage(userId);

    assertThat(response.stats().consultationConversionRate()).isEqualTo(33);
  }

  @Test
  @DisplayName("getMyPage: 당월 경계 [from, to)로 완료·상담전환을 조회한다")
  void getMyPage_monthlyBoundaries() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    User user = mock(User.class);
    given(user.getRole()).willReturn(Role.CERTIFICATED_ADJUSTER);
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));

    service.getMyPage(userId);

    ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(adjusterHomeRepository)
        .countCompletedBetween(eq(userId), fromCaptor.capture(), toCaptor.capture());
    LocalDateTime from = fromCaptor.getValue();
    LocalDateTime to = toCaptor.getValue();
    assertThat(from.getDayOfMonth()).isEqualTo(1);
    assertThat(from.toLocalTime()).isEqualTo(LocalDateTime.MIN.toLocalTime());
    assertThat(to).isEqualTo(from.plusMonths(1));
  }

  @Test
  @DisplayName("getMyPage: 프로필이 없으면 ADJUSTER_NOT_FOUND")
  void getMyPage_profileNotFound() {
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMyPage(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ADJUSTER_NOT_FOUND));
  }

  @Test
  @DisplayName("getMyPage: 사용자 행이 없으면 USER_NOT_FOUND")
  void getMyPage_userNotFound() {
    given(adjusterProfileRepository.findByUserId(userId))
        .willReturn(Optional.of(mock(AdjusterProfile.class)));
    given(userRepository.findById(userId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMyPage(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
  }

  // ---------- getProfile ----------

  @Test
  @DisplayName("getProfile: 최근 후기 2건(item=영문 enum, report 없으면 null)과 handled_case_count를 매핑한다")
  void getProfile_success() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    given(profile.getUserId()).willReturn(userId);
    given(profile.getReviewCount()).willReturn(5);
    given(profile.getRatingMean()).willReturn(new BigDecimal("4.50"));
    given(profile.getCompletedConsultCount()).willReturn(4);

    User user = mock(User.class);
    given(user.getNickname()).willReturn("김사정");

    AdjusterReviewRow withReport = new AdjusterReviewRow(
        "노글리", 5, AccidentType.TRAFFIC, LocalDateTime.of(2026, 1, 2, 0, 0), "빠르고 친절했습니다.");
    AdjusterReviewRow withoutReport = new AdjusterReviewRow(
        "무명", 4, null, LocalDateTime.of(2026, 1, 1, 0, 0), null);
    Page<AdjusterReviewRow> page = new PageImpl<>(List.of(withReport, withoutReport));

    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(adjusterReviewRepository.findReviewRows(eq(userId), any(Pageable.class))).willReturn(page);
    given(reportReviewRepository.countByAdjusterId(userId)).willReturn(9L);
    given(adjusterHomeRepository.countPendingPoolNotHeldBy(userId)).willReturn(7L);

    AdjusterProfileResponse response = service.getProfile(userId);

    assertThat(response.adjusterId()).isEqualTo(userId);
    assertThat(response.nickname()).isEqualTo("김사정");
    assertThat(response.averageRating()).isEqualTo(4.5);
    assertThat(response.reviewCount()).isEqualTo(5);
    assertThat(response.completedConsultCount()).isEqualTo(4);
    assertThat(response.handledCaseCount()).isEqualTo(9L);
    assertThat(response.pendingReviewCount()).isEqualTo(7);
    assertThat(response.recentReviews()).hasSize(2);
    assertThat(response.recentReviews().get(0).item()).isEqualTo("traffic");
    assertThat(response.recentReviews().get(1).item()).isNull();

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(adjusterReviewRepository).findReviewRows(eq(userId), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 2));
  }

  @Test
  @DisplayName("getProfile: 후기가 없으면 average_rating=0.0, recent_reviews=[]")
  void getProfile_newAdjuster() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    given(profile.getUserId()).willReturn(userId);
    given(profile.getReviewCount()).willReturn(0);

    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(mock(User.class)));
    given(adjusterReviewRepository.findReviewRows(eq(userId), any(Pageable.class)))
        .willReturn(new PageImpl<>(List.of()));

    AdjusterProfileResponse response = service.getProfile(userId);

    assertThat(response.averageRating()).isEqualTo(0.0);
    assertThat(response.reviewCount()).isZero();
    assertThat(response.recentReviews()).isEmpty();
    assertThat(response.specialties()).isEmpty();
    assertThat(response.careers()).isEmpty();
    assertThat(response.activityRegion()).isEmpty();
  }

  @Test
  @DisplayName("getProfile: 프로필이 없으면 ADJUSTER_NOT_FOUND")
  void getProfile_profileNotFound() {
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProfile(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ADJUSTER_NOT_FOUND));
  }

  @Test
  @DisplayName("getProfile: 사용자 행이 없으면 USER_NOT_FOUND")
  void getProfile_userNotFound() {
    given(adjusterProfileRepository.findByUserId(userId))
        .willReturn(Optional.of(mock(AdjusterProfile.class)));
    given(userRepository.findById(userId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProfile(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
  }
}
