package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.soma.backend.domain.report.dto.AdjusterReviewListResponse;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterReviewQueryService 단위 테스트")
class AdjusterReviewQueryServiceTest {

  @Mock
  private AdjusterReviewRepository adjusterReviewRepository;
  @Mock
  private UserRepository userRepository;
  @InjectMocks
  private AdjusterReviewQueryService service;

  private final UUID adjusterId = UUID.randomUUID();

  @Test
  @DisplayName("목록을 1-based page 메타와 함께 반환한다")
  void getReviews_success() {
    given(userRepository.existsById(adjusterId)).willReturn(true);
    AdjusterReviewRow row = new AdjusterReviewRow("노글리", 5, null, LocalDateTime.now(), "빠르고 친절했습니다.");
    given(adjusterReviewRepository.findReviewRows(any(), any()))
        .willReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 10), 1));

    AdjusterReviewListResponse response = service.getReviews(adjusterId, 1, 10);

    assertThat(response.list()).hasSize(1);
    assertThat(response.list().get(0).nickname()).isEqualTo("노글리");
    assertThat(response.list().get(0).score()).isEqualTo(5);
    assertThat(response.pagination().page()).isEqualTo(1);
    assertThat(response.pagination().totalElements()).isEqualTo(1);
    assertThat(response.pagination().hasNext()).isFalse();
  }

  @Test
  @DisplayName("사정사(사용자)가 없으면 ADJUSTER_NOT_FOUND(404)")
  void getReviews_notFound() {
    given(userRepository.existsById(adjusterId)).willReturn(false);

    assertThatThrownBy(() -> service.getReviews(adjusterId, 1, 10))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ADJUSTER_NOT_FOUND));
  }
}
