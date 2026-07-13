package com.soma.backend.domain.user.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정 요청. 전화번호·지역·프로필사진만 부분 수정한다(닉네임=실명은 수정 불가).
 * 세 필드 모두 선택이며 넘어온 값만 반영하되, 최소 하나는 있어야 한다({@link #hasNoField()}).
 *
 * @param phoneNumber 변경할 전화번호(하이픈 허용). 실제로 바뀌면 재인증이 필요해 인증 상태가 초기화된다.
 * @param region      변경할 활동/거주 지역(복수 허용, 각 100자 이하). 빈 배열이면 지역을 비운다.
 * @param avatarUrl   변경할 프로필 이미지 URL(500자 이하)
 */
public record UserUpdateRequest(
    @Nullable @Pattern(
        regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$",
        message = "유효한 휴대폰 번호 형식이 아닙니다.") String phoneNumber,
    @Nullable @Size(max = 10, message = "지역은 최대 10개까지 등록할 수 있습니다.")
        List<@Size(max = 100, message = "지역은 각 100자 이하여야 합니다.") String> region,
    @Nullable @Size(max = 500, message = "프로필 이미지 URL은 500자 이하여야 합니다.") String avatarUrl) {

  /**
   * 수정할 필드가 하나도 없는지 여부. 부분 수정 API라 최소 한 필드는 있어야 한다.
   */
  public boolean hasNoField() {
    return phoneNumber == null && region == null && avatarUrl == null;
  }
}
