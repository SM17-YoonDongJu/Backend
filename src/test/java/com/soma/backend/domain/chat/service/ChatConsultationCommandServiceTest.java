package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.ChatEventPublisher;

/**
 * 상담 수락/거절(설계서 §4 ④·⑤, 확정 결정 §0) 단위 테스트. 원자적 상태 전이(내 제안·형제 제안·report·방)와
 * 가드(소유자 아님·검색 방·방 없음)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatConsultationCommandServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ChatMessageRepository chatMessageRepository;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ChatEventPublisher chatEventPublisher;

  @InjectMocks
  private ChatConsultationCommandService service;

  private UUID userId;
  private UUID adjusterId;
  private UUID reportId;
  private UUID reviewId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    reportId = UUID.randomUUID();
    reviewId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  private ChatRoom pipelineRoom(ChatRoomStatus status) {
    return ChatRoomFixture.withId(roomId, userId, adjusterId, reportId, reviewId, status);
  }

  private ReportReview reviewWithId(UUID id, UUID reportId, UUID adjusterId, ReviewStatus status) {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", id);
    ReflectionTestUtils.setField(review, "status", status);
    return review;
  }

  private Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", status);
    ReflectionTestUtils.setField(report, "accidentType", AccidentType.MEDICAL_INDEMNITY);
    return report;
  }

  private void stubMessageSave() {
    given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(inv -> {
      ChatMessage message = inv.getArgument(0);
      ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
      ReflectionTestUtils.setField(message, "createdAt", java.time.LocalDateTime.now());
      return message;
    });
  }

  @Nested
  @DisplayName("accept — 상담 수락")
  class Accept {

    @Test
    @DisplayName("정상 수락: 내 제안 ACCEPTED, 형제 제안 REJECTED, report CLOSED, 내 방 ACTIVE 유지·형제 방 CLOSED")
    void accept_happyPath_transitionsAtomically() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.SENT);
      Report report = reportWithStatus(ReportStatus.COUNSELING);

      UUID siblingAdjusterId = UUID.randomUUID();
      ReportReview siblingReview = reviewWithId(UUID.randomUUID(), reportId, siblingAdjusterId, ReviewStatus.SENT);
      ChatRoom siblingRoom =
          ChatRoomFixture.withId(UUID.randomUUID(), userId, siblingAdjusterId, reportId, UUID.randomUUID(),
              ChatRoomStatus.ACTIVE);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
      given(reportReviewRepository.findByReportId(reportId)).willReturn(List.of(myReview, siblingReview));
      given(chatRoomRepository.findByReportId(reportId)).willReturn(List.of(room, siblingRoom));
      stubMessageSave();

      ConsultationDecisionResponse response = service.accept(userId, roomId);

      assertThat(myReview.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
      assertThat(siblingReview.getStatus()).isEqualTo(ReviewStatus.REJECTED);
      assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
      assertThat(report.getAdjusterId()).isEqualTo(adjusterId);
      assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
      assertThat(siblingRoom.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);

      assertThat(response.chatRoomId()).isEqualTo(roomId);
      assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
      assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
      assertThat(response.reportId()).isEqualTo(reportId);
      assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);

      // 내 방(수락 안내) + 형제 방(종료 안내) 각각 SYSTEM 브로드캐스트 → 2회 발행
      verify(chatEventPublisher, times(2)).publishAfterCommit(any());
    }

    @Test
    @DisplayName("이미 결정된(ACCEPTED/REJECTED) 형제 제안은 재결정하지 않고 그대로 유지한다")
    void accept_skipsAlreadyDecidedSiblings() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.SENT);
      Report report = reportWithStatus(ReportStatus.COUNSELING);

      ReportReview alreadyRejected =
          reviewWithId(UUID.randomUUID(), reportId, UUID.randomUUID(), ReviewStatus.REJECTED);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
      given(reportReviewRepository.findByReportId(reportId)).willReturn(List.of(myReview, alreadyRejected));
      given(chatRoomRepository.findByReportId(reportId)).willReturn(List.of(room));
      stubMessageSave();

      service.accept(userId, roomId);

      assertThat(alreadyRejected.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    @DisplayName("방 소유자(user)가 아니면 CHAT_NOT_ROOM_OWNER(403)")
    void accept_notOwner_throwsForbidden() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

      assertThatThrownBy(() -> service.accept(UUID.randomUUID(), roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_ROOM_OWNER));
    }

    @Test
    @DisplayName("검색 방(report_review_id null)이면 CHAT_CONSULTATION_UNAVAILABLE(409)")
    void accept_searchRoom_throwsConsultationUnavailable() {
      ChatRoom searchRoom = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(searchRoom));

      assertThatThrownBy(() -> service.accept(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE));
    }

    @Test
    @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404)")
    void accept_roomNotFound_throws404() {
      given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> service.accept(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    @Test
    @DisplayName("이미 결정된 내 제안(REJECTED)을 다시 수락하면 INVALID_STATE_TRANSITION(400)"
        + " — 형제 제안·report·방은 건드리지 않고 즉시 실패한다(fail-fast 원자성)")
    void accept_myReviewAlreadyDecided_throwsInvalidTransitionWithoutSideEffects() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.REJECTED);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(reportWithStatus(ReportStatus.COUNSELING)));

      assertThatThrownBy(() -> service.accept(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

      // myReview.accept()가 가장 먼저 실행되어 실패하므로 형제 조회·report 상태 변경·메시지 발행이 전혀 없어야 한다.
      verify(reportReviewRepository, never()).findByReportId(any());
      verify(chatRoomRepository, never()).findByReportId(any());
      verify(chatMessageRepository, never()).save(any());
      verify(chatEventPublisher, never()).publishAfterCommit(any());
    }
  }

  @Nested
  @DisplayName("reject — 상담 거절")
  class Reject {

    @Test
    @DisplayName("정상 거절: 내 제안 REJECTED, report COUNSELING→AWAITING_ADOPTION, 방 CLOSED, 다른 제안 미터치")
    void reject_happyPath_transitionsAtomically() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.SENT);
      Report report = reportWithStatus(ReportStatus.COUNSELING);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
      stubMessageSave();

      ConsultationDecisionResponse response = service.reject(userId, roomId);

      assertThat(myReview.getStatus()).isEqualTo(ReviewStatus.REJECTED);
      assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
      assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);

      assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.CLOSED);
      assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
      assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

      // 거절은 형제 제안·형제 방을 건드리지 않는다(설계서 §0-2) — 조회조차 하지 않아야 한다.
      verify(reportReviewRepository, never()).findByReportId(any());
      verify(chatRoomRepository, never()).findByReportId(any());
      verify(chatEventPublisher, times(1)).publishAfterCommit(any());
    }

    @Test
    @DisplayName("방 소유자(user)가 아니면 CHAT_NOT_ROOM_OWNER(403)")
    void reject_notOwner_throwsForbidden() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

      assertThatThrownBy(() -> service.reject(UUID.randomUUID(), roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_ROOM_OWNER));
    }

    @Test
    @DisplayName("검색 방(report_review_id null)이면 CHAT_CONSULTATION_UNAVAILABLE(409)")
    void reject_searchRoom_throwsConsultationUnavailable() {
      ChatRoom searchRoom = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(searchRoom));

      assertThatThrownBy(() -> service.reject(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE));
    }

    @Test
    @DisplayName("이미 결정된 제안(ACCEPTED)을 다시 거절하면 INVALID_STATE_TRANSITION(400)")
    void reject_alreadyDecided_throwsInvalidTransition() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.ACCEPTED);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(reportWithStatus(ReportStatus.CLOSED)));

      assertThatThrownBy(() -> service.reject(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("report가 COUNSELING이 아니면 INVALID_STATE_TRANSITION(400)"
        + " — DB 원자성(실패 시 미반영)은 통합 테스트에서 검증")
    void reject_reportNotCounseling_throwsInvalidTransition() {
      ChatRoom room = pipelineRoom(ChatRoomStatus.ACTIVE);
      ReportReview myReview = reviewWithId(reviewId, reportId, adjusterId, ReviewStatus.SENT);
      Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);

      given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
      given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(myReview));
      given(reportRepository.findById(reportId)).willReturn(Optional.of(report));

      // 주의: 이 시나리오는 순수 Mockito 단위 테스트라 myReview.reject()가 report.reopenForAdoption()
      // 예외 이전에 인메모리로 먼저 실행된다. 실제 DB 반영 여부(@Transactional 롤백)는 여기서 검증할 수
      // 없으므로 상태 필드는 단언하지 않고, 예외 코드만 확인한다. 실제 원자성(미반영)은
      // ChatConsultationCommandServiceIntegrationTest에서 실제 트랜잭션으로 검증한다.
      assertThatThrownBy(() -> service.reject(userId, roomId))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }
  }
}
