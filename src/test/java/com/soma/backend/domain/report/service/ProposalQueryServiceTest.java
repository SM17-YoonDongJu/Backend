package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** ProposalQueryService 소유 검증(design.md §8) — 본인 리포트만 제안 목록 조회 가능. */
@ExtendWith(MockitoExtension.class)
class ProposalQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @InjectMocks
  private ProposalQueryService service;

  private final UUID reportId = UUID.randomUUID();

  @Test
  void getProposals_throwsReportNotFound_whenReportMissing() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getProposals(UUID.randomUUID(), reportId, 1, 10))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REPORT_NOT_FOUND));
  }

  @Test
  void getProposals_throwsForbidden_whenNotOwner() {
    Report report = Report.createPending(
        UUID.randomUUID(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260709-001");
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));

    assertThatThrownBy(() -> service.getProposals(UUID.randomUUID(), reportId, 1, 10))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }
}
