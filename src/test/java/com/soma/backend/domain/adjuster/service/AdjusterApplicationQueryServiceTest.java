package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.dto.AdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.entity.ActivityScope;
import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.entity.Affiliation;
import com.soma.backend.domain.adjuster.entity.ApplicantProfile;
import com.soma.backend.domain.adjuster.entity.LicenseProof;
import com.soma.backend.domain.adjuster.repository.AdjusterApplicationRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterApplicationQueryService 단위 테스트")
class AdjusterApplicationQueryServiceTest {

  @Mock
  private AdjusterApplicationRepository adjusterApplicationRepository;
  @InjectMocks
  private AdjusterApplicationQueryService service;

  private final UUID userId = UUID.randomUUID();

  @Test
  @DisplayName("신청이 있으면 상태·문서 2종을 담아 반환한다")
  void getMyApplication_success() {
    AdjusterApplication application = AdjusterApplication.create(
        userId,
        new ApplicantProfile("홍길동", "010-1234-5678", 5, "소개"),
        new LicenseProof("제2024-0001호", null),
        new ActivityScope(List.of("신체"), "서울 송파", Affiliation.INDEPENDENT),
        "https://x/reg.pdf");
    given(adjusterApplicationRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
        .willReturn(Optional.of(application));

    AdjusterApplicationResponse response = service.getMyApplication(userId);

    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(response.name()).isEqualTo("홍길동");
    assertThat(response.documents()).hasSize(2);
    assertThat(response.documents())
        .extracting(AdjusterApplicationResponse.Document::type)
        .containsExactly("LICENSE", "REGISTRATION");
  }

  @Test
  @DisplayName("신청 이력이 없으면 POST_NOT_FOUND")
  void getMyApplication_notFound() {
    given(adjusterApplicationRepository.findTopByUserIdOrderByCreatedAtDesc(userId))
        .willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMyApplication(userId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
  }
}
