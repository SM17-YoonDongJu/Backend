package com.soma.backend.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.service.ChatRoomQueryService;
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
 * 목록 keyset 페이지네이션(정렬 last_message_at DESC NULLS LAST, id DESC)의 페이지·null 구간·동일 시각 경계도 검증한다.
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
  @Autowired
  private ChatRoomQueryService chatRoomQueryService;
  @PersistenceContext
  private EntityManager entityManager;

  private User customer;
  private User adjuster;

  @BeforeEach
  void setUp() {
    customer = userRepository.save(User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
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
    User otherCustomer = userRepository.save(
        User.create("타인", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    User otherAdjuster = userRepository.save(
        User.create("타사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    ChatRoom myRoom = persistRoom(customer, adjuster, null, null);
    persistRoom(otherCustomer, otherAdjuster, null, null);

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);

    assertThat(rows).extracting(ChatRoomListRow::chatRoomId).containsExactly(myRoom.getId());
  }

  @Test
  @DisplayName("정렬은 last_message_at DESC NULLS LAST — 최신 활동방이 먼저, 메시지 없는 방은 마지막")
  void findMyRoomRows_ordersByLastMessageAtDescNullsLast() {
    User adjusterA = userRepository.save(
        User.create("사정사A", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    User adjusterB = userRepository.save(
        User.create("사정사B", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    User adjusterC = userRepository.save(
        User.create("사정사C", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));

    ChatRoom olderActivity = persistRoom(customer, adjusterA, null, null);
    ReflectionTestUtils.setField(olderActivity, "lastMessageAt", LocalDateTime.of(2026, 1, 1, 0, 0));
    chatRoomRepository.save(olderActivity);

    ChatRoom newerActivity = persistRoom(customer, adjusterB, null, null);
    ReflectionTestUtils.setField(newerActivity, "lastMessageAt", LocalDateTime.of(2026, 6, 1, 0, 0));
    chatRoomRepository.save(newerActivity);

    ChatRoom noActivity = persistRoom(customer, adjusterC, null, null);
    entityManager.flush();
    entityManager.clear();

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);

    assertThat(rows).extracting(ChatRoomListRow::chatRoomId)
        .containsExactly(newerActivity.getId(), olderActivity.getId(), noActivity.getId());
  }

  @Test
  @DisplayName("파이프라인 방은 review_status를 채우고, 검색 방(제안 없음)은 report_id·review_status 모두 null")
  void findMyRoomRows_reviewStatus_pipelineHasValueAndSearchRoomIsNull() {
    Report report = Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260716-100");
    reportRepository.save(report);
    ReportReview review = reportReviewRepository.save(ReportReview.create(report.getId(), adjuster.getId()));
    ChatRoom pipelineRoom = persistRoom(customer, adjuster, report.getId(), review.getId());

    User searchAdjuster = userRepository.save(
        User.create("검색사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    ChatRoom searchRoom = persistRoom(customer, searchAdjuster, null, null);

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);

    ChatRoomListRow pipelineRow =
        rows.stream().filter(row -> row.chatRoomId().equals(pipelineRoom.getId())).findFirst().orElseThrow();
    assertThat(pipelineRow.reportId()).isEqualTo(report.getId());
    assertThat(pipelineRow.reportReviewId()).isEqualTo(review.getId());
    assertThat(pipelineRow.reviewStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(pipelineRow.caseNo()).isEqualTo("20260716-100");
    assertThat(pipelineRow.accidentType()).isEqualTo(AccidentType.MEDICAL_INDEMNITY);

    ChatRoomListRow searchRow =
        rows.stream().filter(row -> row.chatRoomId().equals(searchRoom.getId())).findFirst().orElseThrow();
    assertThat(searchRow.reportId()).isNull();
    assertThat(searchRow.reportReviewId()).isNull();
    assertThat(searchRow.reviewStatus()).isNull();
    assertThat(searchRow.caseNo()).isNull();
    assertThat(searchRow.accidentType()).isNull();
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

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);
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

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);
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

    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(adjuster.getId(), null, 100);
    ChatRoomListRow row = rows.stream().filter(candidate -> candidate.chatRoomId().equals(room.getId()))
        .findFirst().orElseThrow();

    assertThat(row.unreadCount()).isEqualTo(1L);
  }

  private ChatRoom persistRoomWithLastMessageAt(User owner, LocalDateTime lastMessageAt) {
    ChatRoom room = persistRoom(owner, adjuster, null, null);
    ReflectionTestUtils.setField(room, "lastMessageAt", lastMessageAt);
    return chatRoomRepository.save(room);
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static ChatRoomCursor cursorOf(ChatRoomListRow row) {
    return new ChatRoomCursor(row.lastMessageAt(), row.chatRoomId());
  }

  private static List<UUID> idsOf(List<ChatRoomListRow> rows) {
    return rows.stream().map(ChatRoomListRow::chatRoomId).toList();
  }

  @Test
  @DisplayName("페이지 경계: 방 5개를 limit 3으로 넘기면 3행 → 마지막 행 커서로 남은 2행, 합치면 전체 정렬과 같다")
  void findMyRoomRows_pagination_splitsWithoutDuplicateOrGap() {
    for (int day = 1; day <= 5; day++) {
      persistRoomWithLastMessageAt(customer, LocalDateTime.of(2026, 3, day, 12, 0));
    }
    flushAndClear();
    List<UUID> all = idsOf(chatRoomRepository.findMyRoomRows(customer.getId(), null, 100));

    List<ChatRoomListRow> first = chatRoomRepository.findMyRoomRows(customer.getId(), null, 3);
    List<ChatRoomListRow> second =
        chatRoomRepository.findMyRoomRows(customer.getId(), cursorOf(first.get(first.size() - 1)), 3);

    assertThat(first).hasSize(3);
    assertThat(second).hasSize(2);
    List<UUID> merged = new ArrayList<>(idsOf(first));
    merged.addAll(idsOf(second));
    assertThat(merged).hasSize(5).doesNotHaveDuplicates().containsExactlyElementsOf(all);
    assertThat(first.get(0).lastMessageAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 12, 0));
    assertThat(second.get(1).lastMessageAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 12, 0));
  }

  @Test
  @DisplayName("null 경계: 값 있는 방 2개 → 다음 페이지는 null 구간부터 → N_ 커서로 남은 null 방이 id DESC로 이어진다")
  void findMyRoomRows_pagination_crossesIntoNullSegment() {
    ChatRoom newer = persistRoomWithLastMessageAt(customer, LocalDateTime.of(2026, 5, 2, 9, 0));
    ChatRoom older = persistRoomWithLastMessageAt(customer, LocalDateTime.of(2026, 5, 1, 9, 0));
    for (int index = 0; index < 3; index++) {
      persistRoom(customer, adjuster, null, null);
    }
    flushAndClear();
    List<ChatRoomListRow> all = chatRoomRepository.findMyRoomRows(customer.getId(), null, 100);
    List<UUID> nullSegment = idsOf(all.subList(2, 5));

    List<ChatRoomListRow> page1 = chatRoomRepository.findMyRoomRows(customer.getId(), null, 2);
    assertThat(idsOf(page1)).containsExactly(newer.getId(), older.getId());
    assertThat(cursorOf(page1.get(1)).lastMessageAt()).isNotNull();

    List<ChatRoomListRow> page2 = chatRoomRepository.findMyRoomRows(customer.getId(), cursorOf(page1.get(1)), 2);
    assertThat(page2).extracting(ChatRoomListRow::lastMessageAt).containsOnlyNulls();
    assertThat(idsOf(page2)).containsExactlyElementsOf(nullSegment.subList(0, 2));

    ChatRoomCursor nullCursor = cursorOf(page2.get(1));
    assertThat(nullCursor.lastMessageAt()).isNull();
    List<ChatRoomListRow> page3 = chatRoomRepository.findMyRoomRows(customer.getId(), nullCursor, 2);
    assertThat(idsOf(page3)).containsExactly(nullSegment.get(2));
  }

  @Test
  @DisplayName("동일 last_message_at tie: 같은 시각 방 3개를 limit 1로 넘기면 id DESC로 하나씩, 중복 없이 나온다")
  void findMyRoomRows_pagination_tieBreaksById() {
    LocalDateTime sameTime = LocalDateTime.of(2026, 7, 7, 7, 7, 7);
    for (int index = 0; index < 3; index++) {
      persistRoomWithLastMessageAt(customer, sameTime);
    }
    flushAndClear();
    List<UUID> all = idsOf(chatRoomRepository.findMyRoomRows(customer.getId(), null, 100));

    List<UUID> walked = new ArrayList<>();
    ChatRoomCursor cursor = null;
    for (int step = 0; step < 4; step++) {
      List<ChatRoomListRow> page = chatRoomRepository.findMyRoomRows(customer.getId(), cursor, 1);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(idsOf(page));
      cursor = cursorOf(page.get(0));
    }

    assertThat(walked).hasSize(3).doesNotHaveDuplicates().containsExactlyElementsOf(all);
  }

  @Test
  @DisplayName("참여자 필터 유지: 커서를 넘겨도 다른 사용자의 방은 나오지 않는다")
  void findMyRoomRows_pagination_keepsParticipantFilter() {
    User otherCustomer = userRepository.save(
        User.create("타인", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    ChatRoom mine = persistRoomWithLastMessageAt(customer, LocalDateTime.of(2026, 2, 1, 0, 0));
    ChatRoom othersOlder = persistRoomWithLastMessageAt(otherCustomer, LocalDateTime.of(2026, 1, 1, 0, 0));
    persistRoom(otherCustomer, adjuster, null, null);
    flushAndClear();

    // 다른 사용자 방 위치로 만든 커서여도 결과는 내 방으로 한정된다.
    ChatRoomCursor foreignCursor = new ChatRoomCursor(LocalDateTime.of(2026, 3, 1, 0, 0), othersOlder.getId());
    List<ChatRoomListRow> rows = chatRoomRepository.findMyRoomRows(customer.getId(), foreignCursor, 100);

    assertThat(idsOf(rows)).containsExactly(mine.getId());
  }

  /**
   * PostgreSQL uuid 정렬은 16바이트 부호 없는 사전순이고, 이는 소문자 hex 문자열 사전순과 같다. Java
   * {@link UUID#compareTo}(부호 있는 long 비교)와는 다르므로 기대값을 쿼리 결과와 독립적으로 만들 때 이 비교를 쓴다.
   */
  private static List<UUID> sortedByIdDesc(List<UUID> ids) {
    return ids.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
  }

  @Test
  @DisplayName("tie 순서 독립 검증: 같은 시각 방들은 PostgreSQL uuid 순서(id DESC)로 나온다 — 쿼리 결과가 아닌 기대값과 비교")
  void findMyRoomRows_tieOrder_matchesIndependentIdDesc() {
    LocalDateTime sameTime = LocalDateTime.of(2026, 7, 7, 7, 7, 7, 500_000_000);
    List<UUID> ids = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      ids.add(persistRoomWithLastMessageAt(customer, sameTime).getId());
    }
    flushAndClear();

    List<UUID> walked = new ArrayList<>();
    ChatRoomCursor cursor = null;
    for (int step = 0; step < 4; step++) {
      List<ChatRoomListRow> page = chatRoomRepository.findMyRoomRows(customer.getId(), cursor, 3);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(idsOf(page));
      cursor = cursorOf(page.get(page.size() - 1));
    }

    assertThat(walked).containsExactlyElementsOf(sortedByIdDesc(ids));
  }

  @Test
  @DisplayName("마이크로초 경계: 1µs 차이 방과 같은 µs tie가 섞여도 size 1 순회가 중복·누락 없이 정확한 순서로 끝난다")
  void findMyRoomRows_microsecondBoundaries_walkExactly() {
    LocalDateTime base = LocalDateTime.of(2026, 8, 1, 10, 20, 30, 123_456_000);
    ChatRoom later = persistRoomWithLastMessageAt(customer, base.plusNanos(1_000));
    List<UUID> tie = new ArrayList<>();
    tie.add(persistRoomWithLastMessageAt(customer, base).getId());
    tie.add(persistRoomWithLastMessageAt(customer, base).getId());
    ChatRoom earlier = persistRoomWithLastMessageAt(customer, base.minusNanos(1_000));
    ChatRoom noMessage = persistRoom(customer, adjuster, null, null);
    flushAndClear();

    List<UUID> expected = new ArrayList<>();
    expected.add(later.getId());
    expected.addAll(sortedByIdDesc(tie));
    expected.add(earlier.getId());
    expected.add(noMessage.getId());

    List<UUID> walked = new ArrayList<>();
    ChatRoomCursor cursor = null;
    for (int step = 0; step < 10; step++) {
      List<ChatRoomListRow> page = chatRoomRepository.findMyRoomRows(customer.getId(), cursor, 1);
      if (page.isEmpty()) {
        break;
      }
      walked.addAll(idsOf(page));
      cursor = cursorOf(page.get(0));
    }

    assertThat(walked).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("서비스 커서 왕복(실DB): 서브 µs 나노가 섞인 값도 DB 반올림값으로 인코딩돼 next_cursor 순회가 정확하다")
  void listMyRooms_endToEndCursorWalk_withSubMicroInput() {
    // 저장 시 PostgreSQL timestamp(µs)로 반올림된다(.123456789 → .123457). 커서는 DB에서 읽은 값으로 만들어지므로
    // 다음 요청의 eq 비교가 저장값과 정확히 일치해야 한다.
    LocalDateTime subMicro = LocalDateTime.of(2026, 9, 1, 0, 0, 0, 123_456_789);
    List<UUID> tie = new ArrayList<>();
    tie.add(persistRoomWithLastMessageAt(customer, subMicro).getId());
    tie.add(persistRoomWithLastMessageAt(customer, subMicro).getId());
    tie.add(persistRoomWithLastMessageAt(customer, subMicro).getId());
    ChatRoom older = persistRoomWithLastMessageAt(customer, subMicro.minusSeconds(1));
    List<UUID> nullRooms = new ArrayList<>();
    nullRooms.add(persistRoom(customer, adjuster, null, null).getId());
    nullRooms.add(persistRoom(customer, adjuster, null, null).getId());
    flushAndClear();

    List<UUID> expected = new ArrayList<>(sortedByIdDesc(tie));
    expected.add(older.getId());
    expected.addAll(sortedByIdDesc(nullRooms));

    List<UUID> walked = new ArrayList<>();
    List<Boolean> hasNextFlags = new ArrayList<>();
    String cursor = null;
    for (int step = 0; step < 10; step++) {
      ChatRoomListResponse response = chatRoomQueryService.listMyRooms(customer.getId(), cursor, 1);
      response.rooms().stream().map(ChatRoomSummaryResponse::chatRoomId).forEach(walked::add);
      hasNextFlags.add(response.hasNext());
      if (!response.hasNext()) {
        assertThat(response.nextCursor()).isNull();
        break;
      }
      cursor = response.nextCursor();
    }

    assertThat(walked).containsExactlyElementsOf(expected);
    // 6개를 size 1로: 앞 5번은 has_next=true, 마지막 1번만 false(빈 다음 페이지 요청이 생기지 않는다)
    assertThat(hasNextFlags).containsExactly(true, true, true, true, true, false);
  }
}
