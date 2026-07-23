package com.soma.backend.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 고객 홈 대시보드 QueryDSL 읽기 모델이 실제 PostgreSQL에서 SQL로 번역·실행되는지 검증한다
 * (reports status IN + order/limit, min 집계, report_reviews→users/adjuster_profiles 조인 + text[] specialties
 * projection, chat_rooms 상관 서브쿼리 exists/count). 데이터가 없어도 SQL은 준비·실행되므로 번역 불가·문법
 * 오류가 있으면 여기서 드러난다(사정사 홈 AdjusterHomeRepositoryExecutionTest 관례).
 */
@SpringBootTest
@ActiveProfiles("test")
class UserDashboardRepositoryExecutionTest {

  @Autowired
  private UserDashboardRepository userDashboardRepository;

  @Test
  @DisplayName("findLatestActiveReport — status IN + order/limit projection이 실행된다(빈 DB에서 empty)")
  void findLatestActiveReportExecutes() {
    assertThat(userDashboardRepository.findLatestActiveReport(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("findFirstReviewedAt — min(created_at) 집계가 실행된다(검수 없으면 null)")
  void findFirstReviewedAtExecutes() {
    assertThat(userDashboardRepository.findFirstReviewedAt(UUID.randomUUID())).isNull();
  }

  @Test
  @DisplayName("findProposalItems — report_reviews→users/adjuster_profiles 조인 + text[] specialties projection이 실행된다")
  void findProposalItemsExecutes() {
    assertThat(userDashboardRepository.findProposalItems(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("할 일 카운트(미확인 제안·검수완료) 쿼리가 실행된다(빈 DB에서 0)")
  void todoCountsExecute() {
    UUID userId = UUID.randomUUID();

    assertThat(userDashboardRepository.countUnconfirmedProposals(userId)).isZero();
    assertThat(userDashboardRepository.countReviewCompletedReports(userId)).isZero();
  }

  @Test
  @DisplayName("findTopUnreadChat — 상관 서브쿼리 exists/count + LEFT JOIN이 실행된다(안읽음 없으면 empty)")
  void findTopUnreadChatExecutes() {
    assertThat(userDashboardRepository.findTopUnreadChat(UUID.randomUUID())).isEmpty();
  }
}
