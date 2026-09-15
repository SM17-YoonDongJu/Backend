package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 사건번호 발급기 검증(#309). 형식·문자집합·충돌 재시도를 본다.
 */
@ExtendWith(MockitoExtension.class)
class CaseNoGeneratorTest {

  /** Crockford Base32 — I·L·O·U를 뺀 32자만 허용한다. */
  private static final String CROCKFORD = "[0-9A-HJKMNP-TV-Z]{6}";
  private static final int PREFIX_LENGTH = "yyyyMMdd-".length();

  @Mock
  private ReportRepository reportRepository;
  @InjectMocks
  private CaseNoGenerator caseNoGenerator;

  @Test
  @DisplayName("오늘 날짜 접두어 + Crockford Base32 6자리로 발급한다")
  void generate_returnsTodayPrefixedCrockfordCode() {
    given(reportRepository.existsByCaseNo(anyString())).willReturn(false);

    String caseNo = caseNoGenerator.generate();

    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    assertThat(caseNo).startsWith(today + "-");
    assertThat(caseNo).hasSize(15);
    assertThat(caseNo.substring(PREFIX_LENGTH)).matches(CROCKFORD);
  }

  @Test
  @DisplayName("혼동 문자(I·L·O·U)를 절대 포함하지 않는다 — 전화로 불러주는 값이라 중요하다")
  void generate_neverContainsAmbiguousCharacters() {
    given(reportRepository.existsByCaseNo(anyString())).willReturn(false);

    for (int i = 0; i < 500; i++) {
      String code = caseNoGenerator.generate().substring(PREFIX_LENGTH);
      assertThat(code).matches(CROCKFORD);
      assertThat(code).doesNotContain("I").doesNotContain("L").doesNotContain("O").doesNotContain("U");
    }
  }

  @Test
  @DisplayName("이미 쓰인 번호면 다시 뽑는다")
  void generate_retries_whenCandidateAlreadyExists() {
    given(reportRepository.existsByCaseNo(anyString()))
        .willReturn(true)
        .willReturn(false);

    String caseNo = caseNoGenerator.generate();

    assertThat(caseNo.substring(PREFIX_LENGTH)).matches(CROCKFORD);
    verify(reportRepository, times(2)).existsByCaseNo(anyString());
  }

  @Test
  @DisplayName("재시도 상한까지 전부 중복이면 CASE_NO_GENERATION_FAILED")
  void generate_throws_whenAllAttemptsCollide() {
    given(reportRepository.existsByCaseNo(anyString())).willReturn(true);

    assertThatThrownBy(() -> caseNoGenerator.generate())
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.CASE_NO_GENERATION_FAILED);
    verify(reportRepository, times(5)).existsByCaseNo(anyString());
  }
}
