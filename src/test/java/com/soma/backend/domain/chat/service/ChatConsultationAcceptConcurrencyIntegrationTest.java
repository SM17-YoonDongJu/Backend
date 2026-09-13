package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.chat.ChatRoomFixture;
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
import com.soma.backend.infra.redis.ChatEventPublisher;

/**
 * 같은 사정사의 서로 다른 리포트 2건을 동시에 수락하는 레이스의 실제 test_db 통합 테스트
 * (design.md E12, 이슈 #304).
 *
 * <p>수락은 담당 확정이라 adjuster_profiles의 상담 완료 수를 원천(report_reviews ACCEPTED) 전량 재집계로
 * 갱신한다. 리포트가 다르면 서로의 report 행을 막지 못하므로, {@code findByUserIdForUpdate}의 비관적 잠금이
 * 빠지면 두 트랜잭션이 각자 자기 것만 센 값(1)을 쓰고 나중 커밋이 먼저 커밋을 덮어써 채택 1건이 집계에서
 * 사라진다. 즉 이 테스트는 {@code @Lock(PESSIMISTIC_WRITE)}를 지우면 깨진다.
 *
 * <p>{@code @Transactional}을 두지 않는다 — 두 스레드가 서로의 커밋을 관찰해야 하는 테스트라 각 요청이
 * 실제로 커밋돼야 한다({@code ChatConsultationRejectConcurrencyIntegrationTest} 관례). 커밋한 데이터는
 * {@code @AfterEach}에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("상담 수락 동시성 통합 테스트")
class ChatConsultationAcceptConcurrencyIntegrationTest {

  private static final int TIMEOUT_SECONDS = 30;

  @Autowired
  private ChatConsultationCommandService chatConsultationCommandService;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
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

  /** Redis I/O를 테스트에서 끊는다 — 실제 커밋 경로라 afterCommit 브로드캐스트가 진짜로 발화한다. */
  @MockitoBean
  private ChatEventPublisher chatEventPublisher;

  private UUID customerId;
  private UUID adjusterId;
  private UUID roomAId;
  private UUID roomBId;
  private UUID reviewAId;
  private UUID reviewBId;

  @BeforeEach
  void setUp() {
    User customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    User adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    customerId = customer.getId();
    adjusterId = adjuster.getId();
    saveProfile(adjusterId);

    CounselingCase caseA = saveCounselingCase();
    CounselingCase caseB = saveCounselingCase();
    roomAId = caseA.roomId();
    roomBId = caseB.roomId();
    reviewAId = caseA.reviewId();
    reviewBId = caseB.reviewId();
  }

  @AfterEach
  void cleanUp() {
    // @Transactional 롤백이 없으므로 커밋된 데이터를 직접 정리한다(엔티티 간 FK 제약 미매핑이라 순서 무관).
    chatMessageRepository.deleteAll();
    chatRoomRepository.deleteAll();
    reportReviewRepository.deleteAll();
    reportRepository.deleteAll();
    adjusterProfileRepository.deleteAll();
    userRepository.deleteAll();
  }

  /** 집계 대상 프로필 행 — 자격 승인 플로우가 없어 프로덕션 경로로는 만들 수 없으므로 직접 만든다. */
  private void saveProfile(UUID adjusterUserId) {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", adjusterUserId);
    adjusterProfileRepository.save(profile);
  }

  /**
   * 상담 중인 사건 1건(리포트 + 이 사정사의 제안 + 활성 상담방)을 만든다. 두 사건은 리포트가 서로 달라
   * 형제 관계가 아니다 — 잠금 경합은 오직 adjuster_profiles 한 행에서만 일어난다.
   * 트랜잭션 밖이라 상태 전이를 저장 전에 끝내야 반영된다.
   */
  private CounselingCase saveCounselingCase() {
    Report report = Report.createPending(customerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "ACONC-" + UUID.randomUUID().toString().substring(0, 12));
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    report.applyReviewTransition(ReportStatus.COUNSELING);
    UUID reportId = reportRepository.save(report).getId();

    ReportReview review = new ReportReview(reportId, adjusterId);
    review.startCounseling();
    UUID reviewId = reportReviewRepository.save(review).getId();

    ChatRoom room = ChatRoomFixture.build(customerId, adjusterId, reportId, reviewId, ChatRoomStatus.ACTIVE);
    return new CounselingCase(chatRoomRepository.save(room).getId(), reviewId);
  }

  /** 두 작업을 같은 순간에 출발시키고(래치), 각 스레드가 던진 예외를 모아 돌려준다. */
  private List<Throwable> runConcurrently(Runnable first, Runnable second) throws InterruptedException {
    CountDownLatch startLine = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(2);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (Runnable task : List.of(first, second)) {
        pool.submit(() -> {
          try {
            startLine.await();
            task.run();
          } catch (Throwable ex) {
            failures.add(ex);
          } finally {
            finished.countDown();
          }
        });
      }
      startLine.countDown();
      assertThat(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    return failures;
  }

  private ReviewStatus statusOfReview(UUID reviewId) {
    return reportReviewRepository.findById(reviewId).orElseThrow().getStatus();
  }

  @Test
  @DisplayName("같은 사정사의 서로 다른 리포트 2건을 동시에 수락해도 completed_consult_count=2로 집계된다")
  void concurrentAccepts_accumulateWithoutLostUpdate() throws InterruptedException {
    List<Throwable> failures = runConcurrently(
        () -> chatConsultationCommandService.accept(customerId, roomAId),
        () -> chatConsultationCommandService.accept(customerId, roomBId));

    assertThat(failures).isEmpty();
    assertThat(statusOfReview(reviewAId)).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(statusOfReview(reviewBId)).isEqualTo(ReviewStatus.ACCEPTED);

    // 잠금이 두 재집계를 직렬화하므로 나중 트랜잭션이 먼저 커밋된 채택까지 세고 계산한다(누락 없음).
    AdjusterProfile profile = adjusterProfileRepository.findByUserId(adjusterId).orElseThrow();
    assertThat(profile.getCompletedConsultCount()).isEqualTo(2);
  }

  /** 동시에 수락할 사건 한 건의 식별자 묶음(상담방·제안). */
  private record CounselingCase(UUID roomId, UUID reviewId) {
  }
}
