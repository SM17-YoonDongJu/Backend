package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import com.soma.backend.domain.report.entity.AccidentType;

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
  @DisplayName("findPendingReviewRows — users 조인 + 보류 제외(notExists) 서브쿼리 + 접수 최신순 SQL이 실행된다")
  void pendingReviewRowsExecutes() {
    Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
        null, null, null, UUID.randomUUID(), Set.of(), PageRequest.of(0, 20));

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isZero();
  }

  @Test
  @DisplayName("findPendingReviewRows — 전문분야 매칭 accident_type 우선 정렬(CASE) SQL이 실행된다")
  void pendingReviewRowsSpecialtySortExecutes() {
    Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
        null, null, null, UUID.randomUUID(), Set.of(AccidentType.TRAFFIC, AccidentType.DISABILITY),
        PageRequest.of(0, 20));

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
  }

  @Test
  @DisplayName("findPendingReviewRows — region 필터가 users.region 배열 contains(array_contains)로 실행된다")
  void pendingReviewRowsRegionFilterExecutes() {
    Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
        null, null, "서울", UUID.randomUUID(), Set.of(), PageRequest.of(0, 20));

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

  /**
   * design.md §12.4 C30 — PII 암호화(V34) 이후에도 이 projection이 그대로 실행되는지 확인한다.
   * 셀렉트 대상 배열 3종은 {@code report_reviews}(사정사 검수본, 1단계 평문 유지)라 스코프 밖이고,
   * {@code reports}의 동명 컬럼은 PR-2 게이트 대상이다.
   */
  @Test
  @DisplayName("findCustomerReportDetail — report_reviews 배열 3종 projection SQL이 실행된다(PII 스코프 밖 확인)")
  void customerReportDetailExecutes() {
    CustomerReportDetailRow row = reportRepository.findCustomerReportDetail(UUID.randomUUID());

    assertThat(row).isNull();
  }
}
