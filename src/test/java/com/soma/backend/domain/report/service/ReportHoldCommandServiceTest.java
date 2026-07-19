package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.soma.backend.domain.report.dto.HoldReportRequest;
import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.entity.HoldReason;
import com.soma.backend.domain.report.entity.ReportHold;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#3 보류 추가 유스케이스 단위 테스트(멱등·404·사유 검증 경계). */
@ExtendWith(MockitoExtension.class)
class ReportHoldCommandServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportHoldRepository reportHoldRepository;
  @Mock
  private ReportHoldInitializer reportHoldInitializer;

  @InjectMocks
  private ReportHoldCommandService service;

  private UUID reportId;
  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    reportId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
  }

  private void givenExistingHold(HoldReason reason, String reasonDetail) {
    given(reportHoldRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(new ReportHold(reportId, adjusterId, reason, reasonDetail)));
  }

  @Test
  @DisplayName("존재하지 않는 reportId 추가 시 REPORT_NOT_FOUND(404), 생성 시도 안 함")
  void addReportNotFound() {
    given(reportRepository.existsById(reportId)).willReturn(false);
    HoldReportRequest request = new HoldReportRequest("NEED_MORE_DOCUMENTS", null);

    assertThatThrownBy(() -> service.addHold(reportId, adjusterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    verify(reportHoldInitializer, never()).ensureExists(any(), any(), any(), any());
  }

  @Test
  @DisplayName("프리셋 사유 보류는 멱등 확보 후 held=true, 사유 에코")
  void addHoldWithPresetReason() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    givenExistingHold(HoldReason.SCHEDULE_CONFLICT, null);
    HoldReportRequest request = new HoldReportRequest("SCHEDULE_CONFLICT", null);

    HoldResponse result = service.addHold(reportId, adjusterId, request);

    assertThat(result.held()).isTrue();
    assertThat(result.reportId()).isEqualTo(reportId);
    assertThat(result.reason()).isEqualTo("SCHEDULE_CONFLICT");
    assertThat(result.reasonDetail()).isNull();
    verify(reportHoldInitializer).ensureExists(reportId, adjusterId, HoldReason.SCHEDULE_CONFLICT, null);
  }

  @Test
  @DisplayName("OTHER 사유는 상세 텍스트를 trim해 저장하고 에코한다")
  void addHoldWithOtherReason() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    givenExistingHold(HoldReason.OTHER, "장해진단서, MRI 판독지 필요");
    HoldReportRequest request = new HoldReportRequest("OTHER", "  장해진단서, MRI 판독지 필요  ");

    HoldResponse result = service.addHold(reportId, adjusterId, request);

    assertThat(result.reason()).isEqualTo("OTHER");
    assertThat(result.reasonDetail()).isEqualTo("장해진단서, MRI 판독지 필요");
    verify(reportHoldInitializer).ensureExists(reportId, adjusterId, HoldReason.OTHER, "장해진단서, MRI 판독지 필요");
  }

  @Test
  @DisplayName("프리셋 사유의 공백 상세 텍스트는 null로 정규화한다")
  void blankDetailNormalizedToNull() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    givenExistingHold(HoldReason.OUT_OF_SPECIALTY, null);
    HoldReportRequest request = new HoldReportRequest("OUT_OF_SPECIALTY", "   ");

    HoldResponse result = service.addHold(reportId, adjusterId, request);

    assertThat(result.reasonDetail()).isNull();
    verify(reportHoldInitializer).ensureExists(reportId, adjusterId, HoldReason.OUT_OF_SPECIALTY, null);
  }

  @Test
  @DisplayName("사유가 비어 있으면 MISSING_REQUIRED_FIELD, 생성 시도 안 함")
  void missingReason() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    HoldReportRequest request = new HoldReportRequest("  ", null);

    assertThatThrownBy(() -> service.addHold(reportId, adjusterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
    verify(reportHoldInitializer, never()).ensureExists(any(), any(), any(), any());
  }

  @Test
  @DisplayName("알 수 없는 사유 값이면 VALIDATION_ERROR, 생성 시도 안 함")
  void unknownReason() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    HoldReportRequest request = new HoldReportRequest("NOT_A_REASON", null);

    assertThatThrownBy(() -> service.addHold(reportId, adjusterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
    verify(reportHoldInitializer, never()).ensureExists(any(), any(), any(), any());
  }

  @Test
  @DisplayName("OTHER인데 상세 텍스트가 없으면 MISSING_REQUIRED_FIELD, 생성 시도 안 함")
  void otherWithoutDetail() {
    given(reportRepository.existsById(reportId)).willReturn(true);
    HoldReportRequest request = new HoldReportRequest(HoldReason.OTHER.name(), "   ");

    assertThatThrownBy(() -> service.addHold(reportId, adjusterId, request))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
    verify(reportHoldInitializer, never()).ensureExists(eq(reportId), eq(adjusterId), any(), any());
  }
}
