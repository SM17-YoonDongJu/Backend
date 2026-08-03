package com.soma.backend.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * ChatMessage 커서 페이지네이션(설계서 §4 ②) 실제 test_db 통합 테스트. 정렬은
 * {@code created_at DESC, id DESC}이며, 동시각(tie) 발생 시 id로 안정 정렬되어 페이지 경계에서
 * 유실·중복이 없어야 한다. {@code created_at}은 {@code updatable=false}라 네이티브 UPDATE로 강제해
 * 결정적 타임스탬프를 만든다. {@code @Transactional}로 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatMessageRepositoryImplTest {

  @Autowired
  private ChatMessageRepository chatMessageRepository;
  @Autowired
  private ChatRoomRepository chatRoomRepository;
  @Autowired
  private UserRepository userRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private UUID roomId;
  private UUID senderId;

  @BeforeEach
  void setUp() {
    User customer = userRepository.save(User.create("고객", LocalDate.of(1990, 1, 1), "F", null, Role.USER, null));
    User adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER, null));
    ChatRoom room =
        ChatRoomFixture.build(customer.getId(), adjuster.getId(), null, null, ChatRoomStatus.ACTIVE);
    roomId = chatRoomRepository.save(room).getId();
    senderId = customer.getId();
  }

  /** created_at은 엔티티에서 updatable=false라 네이티브 UPDATE로 강제한다(테스트 전용 결정적 타임스탬프). */
  private ChatMessage persistWithTimestamp(String content, LocalDateTime createdAt) {
    ChatMessage message = ChatMessage.text(roomId, senderId, content);
    ChatMessage saved = chatMessageRepository.save(message);
    entityManager.flush();
    entityManager.createNativeQuery("UPDATE chatroom_messages SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", saved.getId())
        .executeUpdate();
    return saved;
  }

  @Test
  @DisplayName("첫/중간/마지막 페이지: 최신순으로 정확히 나뉘고 마지막 페이지는 has_next=false")
  void findByCursor_paginatesNewestFirstAcrossPages() {
    LocalDateTime t0 = LocalDateTime.of(2026, 7, 16, 10, 0, 0);
    persistWithTimestamp("msg1", t0);
    persistWithTimestamp("msg2", t0.plusSeconds(1));
    persistWithTimestamp("msg3", t0.plusSeconds(2));
    persistWithTimestamp("msg4", t0.plusSeconds(3));
    persistWithTimestamp("msg5", t0.plusSeconds(4));
    persistWithTimestamp("msg6", t0.plusSeconds(5));
    entityManager.flush();
    entityManager.clear();

    int pageSize = 3;
    List<ChatMessage> firstPageRows = chatMessageRepository.findByCursor(roomId, null, null, pageSize + 1);
    boolean firstHasNext = firstPageRows.size() > pageSize;
    List<ChatMessage> firstPage = firstHasNext ? firstPageRows.subList(0, pageSize) : firstPageRows;

    assertThat(firstHasNext).isTrue();
    assertThat(firstPage).extracting(ChatMessage::getContent).containsExactly("msg6", "msg5", "msg4");

    ChatMessage lastOfFirstPage = firstPage.get(firstPage.size() - 1);
    List<ChatMessage> secondPageRows = chatMessageRepository.findByCursor(
        roomId, lastOfFirstPage.getCreatedAt(), lastOfFirstPage.getId(), pageSize + 1);
    boolean secondHasNext = secondPageRows.size() > pageSize;
    List<ChatMessage> secondPage = secondHasNext ? secondPageRows.subList(0, pageSize) : secondPageRows;

    assertThat(secondHasNext).isFalse();
    assertThat(secondPage).extracting(ChatMessage::getContent).containsExactly("msg3", "msg2", "msg1");
  }

  @Test
  @DisplayName("동시각(tie) 메시지도 id 안정 정렬로 커서 페이지네이션 시 유실·중복이 없다")
  void findByCursor_tieBrokenByIdWithoutLossOrDuplication() {
    LocalDateTime t0 = LocalDateTime.of(2026, 7, 16, 10, 0, 0);
    persistWithTimestamp("tie-a", t0);
    persistWithTimestamp("tie-b", t0);
    persistWithTimestamp("mid", t0.plusSeconds(1));
    persistWithTimestamp("tie-c", t0.plusSeconds(2));
    persistWithTimestamp("tie-d", t0.plusSeconds(2));
    entityManager.flush();
    entityManager.clear();

    List<ChatMessage> fullFetch = chatMessageRepository.findByCursor(roomId, null, null, 100);
    assertThat(fullFetch).hasSize(5);

    List<UUID> paginatedIds = new ArrayList<>();
    LocalDateTime cursorCreatedAt = null;
    UUID cursorId = null;
    boolean hasNext = true;
    int guard = 0;
    while (hasNext) {
      guard++;
      assertThat(guard).isLessThanOrEqualTo(10);
      List<ChatMessage> page = chatMessageRepository.findByCursor(roomId, cursorCreatedAt, cursorId, 3);
      hasNext = page.size() > 2;
      List<ChatMessage> trimmed = hasNext ? page.subList(0, 2) : page;
      trimmed.forEach(message -> paginatedIds.add(message.getId()));
      if (hasNext) {
        ChatMessage last = trimmed.get(trimmed.size() - 1);
        cursorCreatedAt = last.getCreatedAt();
        cursorId = last.getId();
      }
    }

    List<UUID> fullIds = fullFetch.stream().map(ChatMessage::getId).toList();
    assertThat(paginatedIds).containsExactlyElementsOf(fullIds);
    assertThat(paginatedIds).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("커서가 없으면(첫 페이지) 방의 최신 메시지부터 반환한다")
  void findByCursor_noCursor_returnsNewestFirst() {
    LocalDateTime t0 = LocalDateTime.of(2026, 7, 16, 9, 0, 0);
    persistWithTimestamp("old", t0);
    persistWithTimestamp("new", t0.plusMinutes(1));
    entityManager.flush();
    entityManager.clear();

    List<ChatMessage> rows = chatMessageRepository.findByCursor(roomId, null, null, 10);

    assertThat(rows).extracting(ChatMessage::getContent).containsExactly("new", "old");
  }

  @Test
  @DisplayName("다른 방의 메시지는 조회되지 않는다")
  void findByCursor_scopesToRoom() {
    User otherCustomer = userRepository.save(User.create("타객", LocalDate.of(1990, 1, 1), "F", null, Role.USER, null));
    User otherAdjuster = userRepository.save(
        User.create("타사정사", LocalDate.of(1985, 1, 1), "M", null, Role.CERTIFICATED_ADJUSTER, null));
    ChatRoom otherRoom =
        ChatRoomFixture.build(otherCustomer.getId(), otherAdjuster.getId(), null, null, ChatRoomStatus.ACTIVE);
    UUID otherRoomId = chatRoomRepository.save(otherRoom).getId();
    chatMessageRepository.save(ChatMessage.text(otherRoomId, otherCustomer.getId(), "다른 방 메시지"));
    persistWithTimestamp("내 방 메시지", LocalDateTime.of(2026, 7, 16, 9, 0, 0));
    entityManager.flush();
    entityManager.clear();

    List<ChatMessage> rows = chatMessageRepository.findByCursor(roomId, null, null, 10);

    assertThat(rows).extracting(ChatMessage::getContent).containsExactly("내 방 메시지");
  }
}
