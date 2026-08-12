package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.dto.UpdateAdjusterProfileRequest;
import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterProfileCommandService 단위 테스트")
class AdjusterProfileCommandServiceTest {

  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private AdjusterProfileQueryService adjusterProfileQueryService;
  @InjectMocks
  private AdjusterProfileCommandService service;

  private final UUID userId = UUID.randomUUID();

  @Test
  @DisplayName("updateProfile: 값이 있는 필드만 반영하고 activityRegion을 text[]로 변환한다")
  void updateProfile_appliesFieldsAndConvertsRegion() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    AdjusterProfileResponse expected = sampleResponse();
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(adjusterProfileQueryService.getProfile(userId)).willReturn(expected);

    UpdateAdjusterProfileRequest request = new UpdateAdjusterProfileRequest(
        "새 태그라인", "새 소개", 7, "서울 강남", null,
        List.of("교통사고"),
        List.of(new UpdateAdjusterProfileRequest.CareerItem("2020-2023", "OO손해사정")));

    AdjusterProfileResponse result = service.updateProfile(userId, request);

    assertThat(result).isSameAs(expected);
    verify(profile).updateProfile(
        "새 태그라인", "새 소개", 7, List.of("서울 강남"),
        List.of("교통사고"), List.of(new AdjusterProfile.Career("2020-2023", "OO손해사정")));
    verify(adjusterProfileRepository).flush();
    verifyNoInteractions(userRepository);
  }

  @Test
  @DisplayName("updateProfile: avatarUrl이 있으면 USERS.avatar_url에 반영한다")
  void updateProfile_updatesAvatarOnUser() {
    AdjusterProfile profile = mock(AdjusterProfile.class);
    User user = mock(User.class);
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.of(profile));
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(adjusterProfileQueryService.getProfile(userId)).willReturn(sampleResponse());

    UpdateAdjusterProfileRequest request = new UpdateAdjusterProfileRequest(
        null, null, null, null, "https://cdn.example.com/a.png", null, null);

    service.updateProfile(userId, request);

    verify(profile).updateProfile(null, null, null, null, null, null);
    verify(user).updateProfile(isNull(), isNull(), isNull(), eq("https://cdn.example.com/a.png"));
  }

  @Test
  @DisplayName("updateProfile: 프로필이 없으면 BusinessException을 던진다")
  void updateProfile_profileNotFound() {
    given(adjusterProfileRepository.findByUserId(userId)).willReturn(Optional.empty());

    UpdateAdjusterProfileRequest request = new UpdateAdjusterProfileRequest(
        "x", null, null, null, null, null, null);

    assertThatThrownBy(() -> service.updateProfile(userId, request))
        .isInstanceOf(BusinessException.class);
  }

  private AdjusterProfileResponse sampleResponse() {
    return new AdjusterProfileResponse(
        userId, "닉네임", "", null, "", "", List.of(), List.of(), 0,
        0.0, 0, List.of(), 0, 0L, 0, LocalDateTime.now());
  }
}
