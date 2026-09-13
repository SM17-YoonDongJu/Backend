package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.service.AdjusterProfileStatsCommandService;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewRequest;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewResponse;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterReviewCommandService 단위 테스트")
class AdjusterReviewCommandServiceTest {

  @Mock
  private AdjusterReviewRepository adjusterReviewRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private AdjusterProfileStatsCommandService adjusterProfileStatsCommandService;
  @InjectMocks
  private AdjusterReviewCommandService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID adjusterId = UUID.randomUUID();

  private CreateAdjusterReviewRequest request() {
    return new CreateAdjusterReviewRequest(5, "빠르고 친절했습니다.");
  }

  @Test
  @DisplayName("자격 있고 중복 아니면 평가를 저장하고 반환한다(수임 사건에 연결)")
  void createReview_success() {
    given(adjusterReviewRepository.existsByUserIdAndAdjusterId(userId, adjusterId)).willReturn(false);
    given(reportReviewRepository.findAcceptedReportIdsForReviewer(adjusterId, userId))
        .willReturn(List.of(UUID.randomUUID()));
    given(adjusterReviewRepository.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));

    CreateAdjusterReviewResponse response = service.createReview(userId, adjusterId, request());

    assertThat(response.adjusterId()).isEqualTo(adjusterId);
    assertThat(response.score()).isEqualTo(5);
    verify(adjusterReviewRepository).saveAndFlush(any());
  }

  /** 저장 순서가 뒤바뀌면 재집계가 방금 등록한 평가를 놓친다(모수 1건 누락). */
  @Test
  @DisplayName("등록에 성공하면 평가를 flush한 뒤 대상 사정사의 평점 집계를 1회 갱신한다")
  void createReview_refreshesAdjusterRatingAfterFlush() {
    given(adjusterReviewRepository.existsByUserIdAndAdjusterId(userId, adjusterId)).willReturn(false);
    given(reportReviewRepository.findAcceptedReportIdsForReviewer(adjusterId, userId))
        .willReturn(List.of(UUID.randomUUID()));
    given(adjusterReviewRepository.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));

    service.createReview(userId, adjusterId, request());

    InOrder order = inOrder(adjusterReviewRepository, adjusterProfileStatsCommandService);
    order.verify(adjusterReviewRepository).saveAndFlush(any());
    order.verify(adjusterProfileStatsCommandService).refreshRating(adjusterId);
  }

  @Test
  @DisplayName("이미 작성한 평가가 있으면 DUPLICATE_RESOURCE(409)")
  void createReview_duplicate() {
    given(adjusterReviewRepository.existsByUserIdAndAdjusterId(userId, adjusterId)).willReturn(true);

    assertThatThrownBy(() -> service.createReview(userId, adjusterId, request()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    verify(adjusterReviewRepository, never()).saveAndFlush(any());
    verifyNoInteractions(adjusterProfileStatsCommandService);
  }

  @Test
  @DisplayName("수임(ACCEPTED) 이력이 없으면 자격 없음 FORBIDDEN(403)")
  void createReview_notEligible() {
    given(adjusterReviewRepository.existsByUserIdAndAdjusterId(userId, adjusterId)).willReturn(false);
    given(reportReviewRepository.findAcceptedReportIdsForReviewer(adjusterId, userId)).willReturn(List.of());

    assertThatThrownBy(() -> service.createReview(userId, adjusterId, request()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    verify(adjusterReviewRepository, never()).saveAndFlush(any());
    verifyNoInteractions(adjusterProfileStatsCommandService);
  }
}
