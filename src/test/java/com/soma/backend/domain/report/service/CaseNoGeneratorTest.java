package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 사건번호 발급기 검증 — 전역 시퀀스({@code core.report_case_seq}) 기반. 형식·zero-pad를 본다.
 */
@ExtendWith(MockitoExtension.class)
class CaseNoGeneratorTest {

  private static final int PREFIX_LENGTH = "yyyyMMdd-".length();

  @Mock
  private ReportRepository reportRepository;
  @InjectMocks
  private CaseNoGenerator caseNoGenerator;

  @Test
  @DisplayName("오늘 날짜 접두어 + 시퀀스값 6자리 zero-pad로 발급한다")
  void generate_returnsTodayPrefixedPaddedSequence() {
    given(reportRepository.nextCaseNoSeq()).willReturn(42L);

    String caseNo = caseNoGenerator.generate();

    String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    assertThat(caseNo).isEqualTo(today + "-000042");
  }

  @Test
  @DisplayName("시퀀스값이 6자리를 넘으면 zero-pad 없이 그대로 쓴다")
  void generate_doesNotPad_whenSequenceExceedsWidth() {
    given(reportRepository.nextCaseNoSeq()).willReturn(1_234_567L);

    String caseNo = caseNoGenerator.generate();

    assertThat(caseNo.substring(PREFIX_LENGTH)).isEqualTo("1234567");
  }

  @Test
  @DisplayName("코드부는 순수 십진수다 — 랜덤/혼동문자를 쓰지 않는다")
  void generate_codeIsPlainDecimal() {
    given(reportRepository.nextCaseNoSeq()).willReturn(7L);

    String code = caseNoGenerator.generate().substring(PREFIX_LENGTH);

    assertThat(code).matches("[0-9]+");
    assertThat(code).isEqualTo("000007");
  }
}
