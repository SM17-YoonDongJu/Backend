package com.soma.backend.domain.adjuster.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 사정사 홈 QueryDSL 읽기 모델이 실제 PostgreSQL에서 SQL로 번역·실행되는지 검증한다
 * (users LEFT JOIN adjuster_profiles + coalesce projection, reports/report_reviews 조인·enum IN·컨버터 projection).
 * 데이터가 없어도 SQL은 준비·실행되므로 번역 불가·문법 오류가 있으면 여기서 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdjusterHomeRepositoryExecutionTest {

  @Autowired
  private AdjusterHomeRepository adjusterHomeRepository;

  @Test
  @DisplayName("findAdjusterIdentity — users LEFT JOIN adjuster_profiles + coalesce projection이 실행된다")
  void findAdjusterIdentityExecutes() {
    AdjusterIdentityRow row = adjusterHomeRepository.findAdjusterIdentity(UUID.randomUUID());

    assertThat(row).isNull();
  }

  @Test
  @DisplayName("대기 풀·진행 중·이번 달 완료 카운트 쿼리가 실행된다(빈 DB에서 0)")
  void countsExecute() {
    UUID adjusterId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.now();

    assertThat(adjusterHomeRepository.countPendingPoolNotHeldBy(adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countPendingPoolNewNotHeldBy(now.minusHours(24), adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countInProgress(adjusterId)).isZero();
    assertThat(adjusterHomeRepository.countCompletedBetween(adjusterId, now.minusDays(30), now)).isZero();
  }

  @Test
  @DisplayName("findInProgressCases — report_reviews·reports 조인 + status IN 조회가 실행된다")
  void findInProgressCasesExecutes() {
    List<InProgressCaseRow> rows = adjusterHomeRepository.findInProgressCases(UUID.randomUUID(), 5);

    assertThat(rows).isEmpty();
  }
}
