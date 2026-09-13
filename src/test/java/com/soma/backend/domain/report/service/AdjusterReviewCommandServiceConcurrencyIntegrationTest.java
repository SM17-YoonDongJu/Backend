package com.soma.backend.domain.report.service;

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
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewRequest;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * 서로 다른 사용자가 같은 사정사를 동시에 평가하는 레이스의 실제 test_db 통합 테스트(design.md E11, 이슈 #304).
 *
 * <p>평점 재집계는 read-modify-write다. 중복 방지 키가 (user, adjuster)라 서로 다른 사용자는 서로를 막지
 * 못하므로, {@code AdjusterProfileRepository.findByUserIdForUpdate}의 비관적 잠금이 빠지면 READ COMMITTED에서
 * 두 트랜잭션이 각자 옛 모수(자기 것 1건)로 집계해 둘 다 {@code review_count = 1}을 쓰고 나중 커밋이 먼저
 * 커밋을 덮어쓴다 — 평가 1건이 영구 누락된다((user, adjuster)당 평생 1회 경로라 재집계로도 복구되지 않는다).
 * 즉 이 테스트는 {@code @Lock(PESSIMISTIC_WRITE)}를 지우면 깨진다.
 *
 * <p>{@code @Transactional}을 두지 않는다 — 두 스레드가 서로의 커밋을 관찰해야 하는 테스트라 각 호출이
 * 실제로 커밋돼야 한다({@code ChatConsultationRejectConcurrencyIntegrationTest} 관례). 커밋한 데이터는
 * {@code @AfterEach}에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("사정사 평가 등록 동시성 통합 테스트")
class AdjusterReviewCommandServiceConcurrencyIntegrationTest {

  private static final int TIMEOUT_SECONDS = 30;

  @Autowired
  private AdjusterReviewCommandService adjusterReviewCommandService;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @Autowired
  private AdjusterReviewRepository adjusterReviewRepository;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;

  private UUID adjusterId;
  private UUID customerAId;
  private UUID customerBId;

  @BeforeEach
  void setUp() {
    User adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    adjusterId = adjuster.getId();
    saveProfile(adjusterId);

    customerAId = saveCustomerWithAcceptedCase("고객A");
    customerBId = saveCustomerWithAcceptedCase("고객B");
  }

  @AfterEach
  void cleanUp() {
    // @Transactional 롤백이 없으므로 커밋된 데이터를 직접 정리한다(엔티티 간 FK 제약 미매핑이라 순서 무관).
    adjusterReviewRepository.deleteAll();
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
   * 평가 자격(수임 이력)을 갖춘 고객 — 본인 리포트를 이 사정사가 ACCEPTED로 채택한 상태.
   * 트랜잭션 밖이라 상태 전이를 저장 전에 끝내야 반영된다.
   */
  private UUID saveCustomerWithAcceptedCase(String nickname) {
    User customer = userRepository.save(
        User.create(nickname, LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    Report report = Report.createPending(customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "RCONC-" + UUID.randomUUID().toString().substring(0, 12));
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    UUID reportId = reportRepository.save(report).getId();

    ReportReview review = new ReportReview(reportId, adjusterId);
    review.accept();
    reportReviewRepository.save(review);
    return customer.getId();
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

  @Test
  @DisplayName("서로 다른 사용자가 같은 사정사를 동시에 평가해도 review_count=2·rating_mean=4.50으로 집계된다")
  void concurrentReviews_accumulateWithoutLostUpdate() throws InterruptedException {
    List<Throwable> failures = runConcurrently(
        () -> adjusterReviewCommandService.createReview(
            customerAId, adjusterId, new CreateAdjusterReviewRequest(5, "친절했습니다.")),
        () -> adjusterReviewCommandService.createReview(
            customerBId, adjusterId, new CreateAdjusterReviewRequest(4, "도움이 됐습니다.")));

    assertThat(failures).isEmpty();
    assertThat(adjusterReviewRepository.count()).isEqualTo(2);

    // 잠금이 두 재집계를 직렬화하므로 나중 트랜잭션이 먼저 커밋된 평가를 보고 계산한다(누락 없음).
    AdjusterProfile profile = adjusterProfileRepository.findByUserId(adjusterId).orElseThrow();
    assertThat(profile.getReviewCount()).isEqualTo(2);
    assertThat(profile.getRatingMean()).isEqualByComparingTo("4.50");
  }
}
