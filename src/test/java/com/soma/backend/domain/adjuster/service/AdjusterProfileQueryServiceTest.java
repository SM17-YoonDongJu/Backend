package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.dto.AdjusterMyProfileResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterProfileQueryService 단위 테스트")
class AdjusterProfileQueryServiceTest {

  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;
  @Mock
  private ReportRepository reportRepository;
  @InjectMocks
  private AdjusterProfileQueryService service;

  private final UUID userId = UUID.randomUUID();

  @Test
  @DisplayName("프로필이 있으면 검수 대기 수(전역 pending)를 함께 담아 반환한다")
  void getMyProfile_success() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(reportRepository.countPending()).willReturn(7L);

    AdjusterMyProfileResponse response = service.getMyProfile(userId);

    assertThat(response.pendingReviewCount()).isEqualTo(7L);
    assertThat(response.reviewCount()).isZero();
    assertThat(response.specialties()).isEmpty();
  }

  @Test
  @DisplayName("프로필이 없으면 ADJUSTER_NOT_FOUND")
  void getMyProfile_notFound() {
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMyProfile(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ADJUSTER_NOT_FOUND));
  }
}
