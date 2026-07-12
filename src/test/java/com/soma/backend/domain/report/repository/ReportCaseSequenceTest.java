package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * report_case_sequences 원자 발급 검증(case_no 경합 차단, design.md §6). 실제 PostgreSQL의
 * INSERT ... ON CONFLICT ... RETURNING 동작(SB4/Hibernate7)을 확인한다. 로컬 docker PostgreSQL 필요.
 * {@code @Transactional}로 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportCaseSequenceTest {

  @Autowired
  private ReportRepository reportRepository;

  @Test
  void nextCaseNoSequence_incrementsPerDayAndIsolatesByDay() {
    LocalDate day = LocalDate.of(2026, 7, 10);

    assertThat(reportRepository.nextCaseNoSequence(day)).isEqualTo(1);
    assertThat(reportRepository.nextCaseNoSequence(day)).isEqualTo(2);
    assertThat(reportRepository.nextCaseNoSequence(day)).isEqualTo(3);
    assertThat(reportRepository.nextCaseNoSequence(day.plusDays(1))).isEqualTo(1);
  }
}
