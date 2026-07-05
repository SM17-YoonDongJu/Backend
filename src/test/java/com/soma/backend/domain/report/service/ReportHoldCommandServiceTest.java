package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#3 보류 추가 유스케이스 단위 테스트(멱등·404 경계). */
@ExtendWith(MockitoExtension.class)
class ReportHoldCommandServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportHoldRepository reportHoldRepository;

  @InjectMocks
  private ReportHoldCommandService service;

  private UUID reportId;
  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    reportId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
  }

  @Test
  @DisplayName("존재하지 않는 reportId 추가 시 REPORT_NOT_FOUND(404), insert 안 함")
  void addReportNotFound() {
    given(reportRepository.existsById(reportId)).willReturn(false);

    assertThatThrownBy(() -> service.addHold(reportId, adjusterId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    verify(reportHoldRepository, never()).insertIfAbsent(any(), any());
  }

  @Test
  @DisplayName("보류 추가는 멱등 insert 호출 후 held=true")
  void addHoldIdempotent() {
    given(reportRepository.existsById(reportId)).willReturn(true);

    HoldResponse result = service.addHold(reportId, adjusterId);

    assertThat(result.held()).isTrue();
    assertThat(result.reportId()).isEqualTo(reportId);
    verify(reportHoldRepository).insertIfAbsent(reportId, adjusterId);
  }
}
