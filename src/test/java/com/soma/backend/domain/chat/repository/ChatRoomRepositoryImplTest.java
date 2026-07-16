package com.soma.backend.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * ChatRoom 목록 QueryDSL(설계서 §4 ①) 실제 test_db 통합 테스트. 상관 서브쿼리(unread_count)·LEFT JOIN
 * (review_status)·정렬(last_message_at DESC NULLS LAST)·내 방 필터가 실제 SQL로 정확히 실행되는지 검증한다.
 * {@code @Transactional}로 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatRoomRepositoryImplTest {

  @Autowired
  private ChatRoomRepository chatRoomRepository;
  @Autowired
  private ChatMessageRepository chatMessageRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private UserRepository userRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private User customer;
  private User adjuster;

  @BeforeEach
  void setUp() {
    customer = userRepository.save(User.create("고객", LocalDate.of(1990, 1, 1), "F", null, Role.USER));
    adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
  }

  private ChatRoom persistRoom(User user, User adjusterUser, UUID reportId, UUID reportReviewId) {
    ChatRoom room =
        ChatRoomFixture.build(user.getId(), adjusterUser.getId(), reportId, reportReviewId, ChatRoomStatus.ACTIVE);
    return chatRoomRepository.save(room);
  }

  private ChatMessage persistMessage(UUID roomId, UUID senderId, String content) {
    ChatMessage message = senderId == null
        ? ChatMessage.system(roomId, content)
        : ChatMessage.text(roomId, senderId, content);
    ChatMessage saved = chatMessageRepository.save(message);
    entityManager.flush();
    return saved;
  }

  @Test
  @DisplayName("내가 참여한 방만 반환한다(타인의 방은 제외)")
  void findMyRoomRows_returnsOnlyMyRooms() {
    User otherCustomer = userRepository.save(User.create("타인", LocalDate.of(1990, 1, 1), "F", null, Role.USER));
    User otherAdjuster = userRepository.save(
        User.create("타사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
    ChatRoom myRoom = persistRoom(customer, adjuster, null, null);
    persistRoom(otherCustomer, otherAdjuster, null, null);

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId());

    assertThat(rows).extracting(ChatRoomListRow::chatRoomId).containsExactly(myRoom.getId());
  }

  @Test
  @DisplayName("정렬은 last_message_at DESC NULLS LAST — 최신 활동방이 먼저, 메시지 없는 방은 마지막")
  void findMyRoomRows_ordersByLastMessageAtDescNullsLast() {
    User adjusterA = userRepository.save(
        User.create("사정사A", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
    User adjusterB = userRepository.save(
        User.create("사정사B", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
    User adjusterC = userRepository.save(
        User.create("사정사C", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));

    ChatRoom olderActivity = persistRoom(customer, adjusterA, null, null);
    ReflectionTestUtils.setField(olderActivity, "lastMessageAt", LocalDateTime.of(2026, 1, 1, 0, 0));
    chatRoomRepository.save(olderActivity);

    ChatRoom newerActivity = persistRoom(customer, adjusterB, null, null);
    ReflectionTestUtils.setField(newerActivity, "lastMessageAt", LocalDateTime.of(2026, 6, 1, 0, 0));
    chatRoomRepository.save(newerActivity);

    ChatRoom noActivity = persistRoom(customer, adjusterC, null, null);
    entityManager.flush();
    entityManager.clear();

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId());

    assertThat(rows).extracting(ChatRoomListRow::chatRoomId)
        .containsExactly(newerActivity.getId(), olderActivity.getId(), noActivity.getId());
  }

  @Test
  @DisplayName("파이프라인 방은 review_status를 채우고, 검색 방(제안 없음)은 report_id·review_status 모두 null")
  void findMyRoomRows_reviewStatus_pipelineHasValueAndSearchRoomIsNull() {
    Report report = Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260716-100");
    reportRepository.save(report);
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster.getId()));
    ChatRoom pipelineRoom = persistRoom(customer, adjuster, report.getId(), review.getId());

    User searchAdjuster = userRepository.save(
        User.create("검색사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER));
    ChatRoom searchRoom = persistRoom(customer, searchAdjuster, null, null);

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId());

    ChatRoomListRow pipelineRow =
        rows.stream().filter(row -> row.chatRoomId().equals(pipelineRoom.getId())).findFirst().orElseThrow();
    assertThat(pipelineRow.reportId()).isEqualTo(report.getId());
    assertThat(pipelineRow.reportReviewId()).isEqualTo(review.getId());
    assertThat(pipelineRow.reviewStatus()).isEqualTo(ReviewStatus.SENT);

    ChatRoomListRow searchRow =
        rows.stream().filter(row -> row.chatRoomId().equals(searchRoom.getId())).findFirst().orElseThrow();
    assertThat(searchRow.reportId()).isNull();
    assertThat(searchRow.reportReviewId()).isNull();
    assertThat(searchRow.reviewStatus()).isNull();
  }

  @Test
  @DisplayName("안읽음 = 상대가 보낸 메시지 중 내 읽음 커서 이후 — 내 메시지·SYSTEM은 제외")
  void findMyRoomRows_unreadCount_excludesMineAndSystemAndCountsOnlyAfterMyCursor() {
    ChatRoom room = persistRoom(customer, adjuster, null, null);

    persistMessage(room.getId(), customer.getId(), "내가 보낸 메시지(제외 대상)");
    ChatMessage beforeCursor = persistMessage(room.getId(), adjuster.getId(), "커서 이전 상대 메시지(읽음)");
    persistMessage(room.getId(), null, "SYSTEM 메시지(제외 대상)");

    ReflectionTestUtils.setField(room, "userLastReadAt", beforeCursor.getCreatedAt());
    chatRoomRepository.save(room);
    entityManager.flush();

    persistMessage(room.getId(), adjuster.getId(), "커서 이후 상대 메시지 1(안읽음)");
    persistMessage(room.getId(), adjuster.getId(), "커서 이후 상대 메시지 2(안읽음)");
    entityManager.flush();
    entityManager.clear();

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId());
    ChatRoomListRow row = rows.stream().filter(candidate -> candidate.chatRoomId().equals(room.getId()))
        .findFirst().orElseThrow();

    assertThat(row.unreadCount()).isEqualTo(2L);
  }

  @Test
  @DisplayName("읽음 커서가 null(한 번도 안 읽음)이면 상대 메시지 전부가 안읽음이다")
  void findMyRoomRows_unreadCount_nullCursorMeansAllCounterpartMessagesUnread() {
    ChatRoom room = persistRoom(customer, adjuster, null, null);
    persistMessage(room.getId(), adjuster.getId(), "메시지1");
    persistMessage(room.getId(), adjuster.getId(), "메시지2");
    persistMessage(room.getId(), adjuster.getId(), "메시지3");
    entityManager.flush();
    entityManager.clear();

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId());
    ChatRoomListRow row = rows.stream().filter(candidate -> candidate.chatRoomId().equals(room.getId()))
        .findFirst().orElseThrow();

    assertThat(row.unreadCount()).isEqualTo(3L);
  }

  @Test
  @DisplayName("사정사 시점 조회에서는 adjuster_last_read_at 커서가 적용된다")
  void findMyRoomRows_unreadCount_fromAdjusterPerspectiveUsesAdjusterCursor() {
    ChatRoom room = persistRoom(customer, adjuster, null, null);
    ChatMessage read = persistMessage(room.getId(), customer.getId(), "고객이 보낸 메시지(읽음)");
    ReflectionTestUtils.setField(room, "adjusterLastReadAt", read.getCreatedAt());
    chatRoomRepository.save(room);
    entityManager.flush();

    persistMessage(room.getId(), customer.getId(), "고객이 보낸 메시지(안읽음)");
    entityManager.flush();
    entityManager.clear();

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(adjuster.getId());
    ChatRoomListRow row = rows.stream().filter(candidate -> candidate.chatRoomId().equals(room.getId()))
        .findFirst().orElseThrow();

    assertThat(row.unreadCount()).isEqualTo(1L);
  }
}
