package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.CreateAdjusterReviewRequest;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.service.AdjusterReviewCommandService;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * 평가 등록(POST /adjusters/{adjusterId}/reviews) 직후 adjuster_profiles의 평점 비정규화 컬럼이 실제로
 * 갱신되는지 test_db에서 검증한다(이슈 #304 AC 1). 단위 테스트는 "집계 서비스를 호출했다"까지만 보므로,
 * JPQL 집계·자동 플러시·더티 체킹이 실제 DB에서 맞물리는지는 여기서만 확인된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdjusterRatingSyncIntegrationTest {

  @Autowired
  private AdjusterReviewCommandService adjusterReviewCommandService;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private User adjuster;

  @BeforeEach
  void setUp() {
    adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
  }

  private void saveProfile(UUID userId) {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", userId);
    adjusterProfileRepository.save(profile);
  }

  /** 평가 자격(수임 이력)을 만든다 — 해당 사용자의 리포트를 이 사정사가 ACCEPTED로 채택한 상태. */
  private User customerWithAcceptedCase() {
    User customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    Report report = reportRepository.save(Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "RSYNC-" + UUID.randomUUID()));
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster.getId()));
    review.accept();
    return customer;
  }

  private AdjusterProfile reloadProfile() {
    entityManager.flush();
    entityManager.clear();
    return adjusterProfileRepository.findByUserId(adjuster.getId()).orElseThrow();
  }

  @Test
  @DisplayName("첫 평가(5점) 등록 직후 rating_mean=5.00·review_count=1이 프로필에 반영된다")
  void createReview_syncsFirstRating() {
    saveProfile(adjuster.getId());
    User customer = customerWithAcceptedCase();

    adjusterReviewCommandService.createReview(
        customer.getId(), adjuster.getId(), new CreateAdjusterReviewRequest(5, "친절했습니다."));

    AdjusterProfile profile = reloadProfile();
    assertThat(profile.getRatingMean()).isEqualByComparingTo("5.00");
    assertThat(profile.getReviewCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("서로 다른 사용자가 5·4·4점을 등록하면 rating_mean=4.33(scale 2 HALF_UP)·review_count=3")
  void createReview_syncsAverageAcrossReviewers() {
    saveProfile(adjuster.getId());

    for (int score : new int[] {5, 4, 4}) {
      User customer = customerWithAcceptedCase();
      adjusterReviewCommandService.createReview(
          customer.getId(), adjuster.getId(), new CreateAdjusterReviewRequest(score, "후기"));
    }

    AdjusterProfile profile = reloadProfile();
    assertThat(profile.getRatingMean()).isEqualByComparingTo("4.33");
    assertThat(profile.getRatingMean().scale()).isEqualTo(2);
    assertThat(profile.getReviewCount()).isEqualTo(3);
  }

  /**
   * adjuster_profiles 행을 만드는 프로덕션 경로가 아직 없어(자격 승인 플로우 미구현) 실제로 비어 있을 수
   * 있다. 집계는 부수 갱신이므로 행이 없다고 사용자의 평가 등록 자체가 실패해선 안 된다.
   */
  @Test
  @DisplayName("프로필 행이 없는 사정사여도 평가 등록은 성공한다(집계만 건너뛴다)")
  void createReview_succeedsWithoutProfileRow() {
    User customer = customerWithAcceptedCase();

    assertThatCode(() -> adjusterReviewCommandService.createReview(
        customer.getId(), adjuster.getId(), new CreateAdjusterReviewRequest(5, "후기")))
        .doesNotThrowAnyException();

    assertThat(adjusterProfileRepository.findByUserId(adjuster.getId())).isEmpty();
  }
}
