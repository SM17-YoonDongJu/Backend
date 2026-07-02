package com.soma.backend.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.report.application.dto.HoldToggleResult;
import com.soma.backend.report.domain.model.Report;
import com.soma.backend.report.domain.model.ReportHold;
import com.soma.backend.report.domain.repository.ReportHoldRepository;
import com.soma.backend.report.domain.repository.ReportRepository;

/** API#3 보류 토글 유스케이스 단위 테스트(§10 경계 케이스). */
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
  @DisplayName("존재하지 않는 reportId면 REPORT_NOT_FOUND(404)")
  void reportNotFound() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.toggle(reportId, adjusterId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    verify(reportHoldRepository, never()).save(any());
  }

  @Test
  @DisplayName("hold가 없으면 생성하고 held=true 반환")
  void toggleCreatesHold() {
    given(reportRepository.findById(reportId)).willReturn(Optional.of(BeanUtils.instantiateClass(Report.class)));
    given(reportHoldRepository.findByReportIdAndAdjusterId(reportId, adjusterId)).willReturn(Optional.empty());

    HoldToggleResult result = service.toggle(reportId, adjusterId);

    assertThat(result.held()).isTrue();
    assertThat(result.reportId()).isEqualTo(reportId);
    verify(reportHoldRepository).save(any(ReportHold.class));
    verify(reportHoldRepository, never()).delete(any());
  }

  @Test
  @DisplayName("hold가 이미 있으면 삭제하고 held=false 반환(토글 왕복)")
  void toggleDeletesHold() {
    ReportHold existing = new ReportHold(reportId, adjusterId);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(BeanUtils.instantiateClass(Report.class)));
    given(reportHoldRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(existing));

    HoldToggleResult result = service.toggle(reportId, adjusterId);

    assertThat(result.held()).isFalse();
    verify(reportHoldRepository).delete(existing);
    verify(reportHoldRepository, never()).save(any());
  }
}
