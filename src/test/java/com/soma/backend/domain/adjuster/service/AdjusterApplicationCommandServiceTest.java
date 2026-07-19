package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationRequest;
import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.entity.ApplicationStatus;
import com.soma.backend.domain.adjuster.entity.DocumentStatus;
import com.soma.backend.domain.adjuster.repository.AdjusterApplicationRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterApplicationCommandService 단위 테스트")
class AdjusterApplicationCommandServiceTest {

  @Mock
  private AdjusterApplicationRepository adjusterApplicationRepository;
  @Mock
  private UserRepository userRepository;
  @InjectMocks
  private AdjusterApplicationCommandService service;

  private final UUID userId = UUID.randomUUID();

  private CreateAdjusterApplicationRequest request(String licenseNo, String licenseImageUrl) {
    return new CreateAdjusterApplicationRequest(
        "홍길동", "신체", licenseNo, licenseImageUrl, 5, "소개",
        "INDEPENDENT", "서울 송파", "https://x/reg.pdf");
  }

  private User userWithRole(Role role) {
    return User.create("홍길동", LocalDate.of(1990, 1, 1), "MALE", "010-1234-5678", role);
  }

  @Test
  @DisplayName("정상 신청이면 문서 2종 생성·역할 전이·저장하고 PENDING을 반환한다")
  void apply_success() {
    given(adjusterApplicationRepository.existsByUserIdAndStatus(userId, ApplicationStatus.PENDING))
        .willReturn(false);
    User user = userWithRole(Role.USER);
    given(userRepository.findById(userId)).willReturn(Optional.of(user));

    CreateAdjusterApplicationResponse response = service.apply(userId, request("제2024-0001호", null));

    assertThat(response.status()).isEqualTo("PENDING");
    assertThat(user.getRole()).isEqualTo(Role.UNCERTIFICATED_ADJUSTER);

    ArgumentCaptor<AdjusterApplication> captor = ArgumentCaptor.forClass(AdjusterApplication.class);
    verify(adjusterApplicationRepository).save(captor.capture());
    AdjusterApplication saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getDocuments()).hasSize(2);
    assertThat(saved.getDocuments())
        .allSatisfy(doc -> assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING));
  }

  @Test
  @DisplayName("자격증 번호·파일이 모두 없으면 MISSING_REQUIRED_FIELD")
  void apply_missingLicenseProof() {
    assertThatThrownBy(() -> service.apply(userId, request(null, null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    verify(adjusterApplicationRepository, never()).save(any());
  }

  @Test
  @DisplayName("진행 중(PENDING) 신청이 있으면 DUPLICATE_RESOURCE")
  void apply_duplicatePending() {
    given(adjusterApplicationRepository.existsByUserIdAndStatus(userId, ApplicationStatus.PENDING))
        .willReturn(true);

    assertThatThrownBy(() -> service.apply(userId, request("제2024-0001호", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    verify(adjusterApplicationRepository, never()).save(any());
  }

  @Test
  @DisplayName("이미 사정사(role≠USER)면 역할 전이에서 DUPLICATE_RESOURCE")
  void apply_alreadyAdjuster() {
    given(adjusterApplicationRepository.existsByUserIdAndStatus(userId, ApplicationStatus.PENDING))
        .willReturn(false);
    User adjuster = userWithRole(Role.UNCERTIFICATED_ADJUSTER);
    given(userRepository.findById(userId)).willReturn(Optional.of(adjuster));

    assertThatThrownBy(() -> service.apply(userId, request("제2024-0001호", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    verify(adjusterApplicationRepository, never()).save(any());
  }
}
