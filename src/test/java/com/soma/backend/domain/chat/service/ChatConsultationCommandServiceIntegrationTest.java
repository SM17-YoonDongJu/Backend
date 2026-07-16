package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
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
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 상담 수락/거절(설계서 §4 ④·⑤) 실제 test_db 통합 테스트. Mockito 단위 테스트로는 확인할 수 없는
 * 실제 JPA 영속화·조회(findByReportId 등)를 거친 상태 전이를 검증한다. {@code @Transactional}로
 * 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatConsultationCommandServiceIntegrationTest {

  @Autowired
  private ChatConsultationCommandService chatConsultationCommandService;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private ChatRoomRepository chatRoomRepository;
  @Autowired
  private ChatMessageRepository chatMessageRepository;

  private User customer;
  private User adjuster1;
  private User adjuster2;

  @BeforeEach
  void setUp() {
    customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, Role.USER));
    adjuster1 = userRepository.save(
        User.create("사정사1", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
    adjuster2 = userRepository.save(
        User.create("사정사2", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
  }

  private Report counselingReport() {
    Report report = Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260716-001");
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    report.applyReviewTransition(ReportStatus.COUNSELING);
    return reportRepository.save(report);
  }

  private ChatRoom savedRoom(UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    ChatRoom room = ChatRoomFixture.build(userId, adjusterId, reportId, reportReviewId, ChatRoomStatus.ACTIVE);
    return chatRoomRepository.save(room);
  }

  @Test
  @DisplayName("정상 수락: 내 제안 ACCEPTED·형제 제안 REJECTED·report CLOSED·내 방 ACTIVE 유지·형제 방 CLOSED"
      + " + SYSTEM 메시지 저장")
  void accept_persistsAtomicTransitionAcrossAggregates() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ReportReview siblingReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());
    ChatRoom siblingRoom = savedRoom(customer.getId(), adjuster2.getId(), report.getId(), siblingReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.accept(customer.getId(), myRoom.getId());

    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);

    ReportReview reloadedMyReview = reportReviewRepository.findById(myReview.getId()).orElseThrow();
    ReportReview reloadedSibling = reportReviewRepository.findById(siblingReview.getId()).orElseThrow();
    Report reloadedReport = reportRepository.findById(report.getId()).orElseThrow();
    ChatRoom reloadedMyRoom = chatRoomRepository.findById(myRoom.getId()).orElseThrow();
    ChatRoom reloadedSiblingRoom = chatRoomRepository.findById(siblingRoom.getId()).orElseThrow();

    assertThat(reloadedMyReview.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(reloadedSibling.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(reloadedReport.getStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(reloadedReport.getAdjusterId()).isEqualTo(adjuster1.getId());
    assertThat(reloadedMyRoom.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(reloadedSiblingRoom.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);

    List<ChatMessage> myRoomMessages = chatMessageRepository.findByCursor(myRoom.getId(), null, null, 10);
    assertThat(myRoomMessages).hasSize(1);
    assertThat(myRoomMessages.get(0).getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
    assertThat(myRoomMessages.get(0).getSenderId()).isNull();
  }

  @Test
  @DisplayName("정상 거절: 내 제안 REJECTED·report AWAITING_ADOPTION·방 CLOSED, 다른 제안은 유지")
  void reject_persistsAtomicTransitionAndKeepsOtherReviews() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ReportReview otherReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.reject(customer.getId(), myRoom.getId());

    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

    ReportReview reloadedMyReview = reportReviewRepository.findById(myReview.getId()).orElseThrow();
    ReportReview reloadedOther = reportReviewRepository.findById(otherReview.getId()).orElseThrow();
    Report reloadedReport = reportRepository.findById(report.getId()).orElseThrow();
    ChatRoom reloadedRoom = chatRoomRepository.findById(myRoom.getId()).orElseThrow();

    assertThat(reloadedMyReview.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(reloadedOther.getStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(reloadedReport.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(reloadedRoom.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
  }

  @Test
  @DisplayName("소유자가 아닌 사용자가 수락 시도하면 CHAT_NOT_ROOM_OWNER(403)")
  void accept_notOwner_throwsForbidden() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());

    assertThatThrownBy(() -> chatConsultationCommandService.accept(UUID.randomUUID(), myRoom.getId()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_ROOM_OWNER));
  }

  @Test
  @DisplayName("검색 방(report_review_id null)에서 수락 시도하면 CHAT_CONSULTATION_UNAVAILABLE(409)")
  void accept_searchRoom_throwsConsultationUnavailable() {
    ChatRoom searchRoom = savedRoom(customer.getId(), adjuster1.getId(), null, null);

    assertThatThrownBy(() -> chatConsultationCommandService.accept(customer.getId(), searchRoom.getId()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE));
  }
}
