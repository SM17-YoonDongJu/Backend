package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * QueryDSL 조회가 실제 PostgreSQL에서 SQL로 번역·실행되는지 검증한다(엔티티 조인·상관 서브쿼리·컨버터 projection).
 * 데이터가 없어도 SQL은 준비·실행되므로, 번역 불가·문법 오류가 있으면 여기서 드러난다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportQuerydslExecutionTest {

  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;

  @Test
  @DisplayName("findPendingReviewRows — users 엔티티 조인 + issueCount/held 상관 서브쿼리 SQL이 실행된다")
  void pendingReviewRowsExecutes() {
    Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
        null, null, null, UUID.randomUUID(), PageRequest.of(0, 20));

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isZero();
  }

  @Test
  @DisplayName("findPendingReviewRows — region 필터가 users.region 배열 contains(array_contains)로 실행된다")
  void pendingReviewRowsRegionFilterExecutes() {
    Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
        null, null, "서울", UUID.randomUUID(), PageRequest.of(0, 20));

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
  }

  @Test
  @DisplayName("findRegionByReportId — users.region 배열 컬럼 단일 조회(fetchOne)가 실행된다")
  void findRegionByReportIdExecutes() {
    List<String> region = reportRepository.findRegionByReportId(UUID.randomUUID());

    assertThat(region).isNull();
  }

  @Test
  @DisplayName("findReviewedReportRows — reports·users 엔티티 조인 + 카운트 쿼리가 실행된다")
  void reviewedReportRowsExecutes() {
    Page<ReviewedReportRow> page = reportReviewRepository.findReviewedReportRows(
        UUID.randomUUID(), null, null, null, PageRequest.of(0, 20));

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
  }
}
