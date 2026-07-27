package com.soma.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.user.dto.UserDashboardResponse;
import com.soma.backend.domain.user.dto.UserDashboardResponse.ProposalSummary;
import com.soma.backend.domain.user.dto.UserDashboardResponse.Todos;
import com.soma.backend.domain.user.repository.ActiveReportRow;
import com.soma.backend.domain.user.repository.ProposalItemRow;
import com.soma.backend.domain.user.repository.UnreadChatRow;
import com.soma.backend.domain.user.repository.UserDashboardRepository;

/**
 * 고객 홈 BFF 집계 유스케이스 단위 테스트. report_count는 기존 집계 재사용, 대표 활성 리포트·제안 요약(min·max·
 * 중앙값 평균)·speciality 파생·null 섹션 처리·"지금 할 일"(todos) 조립을 검증한다. 조회는 두 읽기 모델
 * (ReportRepository·UserDashboardRepository)로 위임한다.
 */
@ExtendWith(MockitoExtension.class)
class UserDashboardQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;

  @Mock
  private UserDashboardRepository userDashboardRepository;

  @InjectMocks
  private UserDashboardQueryService service;

  private UUID userId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
  }

  @Test
  @DisplayName("대표 활성 리포트+제안이 있으면 active_report(proposal_count)·proposal_summary(min·max·avg·items)를 조립한다")
  void assemblesActiveReportAndProposalSummary() {
    UUID reportId = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.now().minusDays(3);
    LocalDateTime firstReviewedAt = LocalDateTime.now().minusDays(1);
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.of(
        new ActiveReportRow(reportId, "무릎 십자인대 파열", AccidentType.MEDICAL_INDEMNITY,
            ReportStatus.AWAITING_ADOPTION, createdAt)));
    given(userDashboardRepository.findFirstReviewedAt(reportId)).willReturn(firstReviewedAt);
    given(userDashboardRepository.findProposalItems(reportId)).willReturn(List.of(
        proposalItem(1000L, 3000L, List.of("근골격계", "신경")),
        proposalItem(2000L, 4000L, null)));

    UserDashboardResponse result = service.getDashboard(userId);

    assertThat(result.reportCount()).isEqualTo(3L);
    assertThat(result.activeReport().reportId()).isEqualTo(reportId);
    assertThat(result.activeReport().accidentType()).isEqualTo("medical_indemnity");
    assertThat(result.activeReport().status()).isEqualTo("AWAITING_ADOPTION");
    assertThat(result.activeReport().firstReviewedAt()).isEqualTo(firstReviewedAt);
    assertThat(result.activeReport().proposalCount()).isEqualTo(2L);

    ProposalSummary summary = result.proposalSummary();
    assertThat(summary.count()).isEqualTo(2);
    assertThat(summary.minAmount()).isEqualTo(1000L);
    assertThat(summary.maxAmount()).isEqualTo(4000L);
    // 중앙값 평균 = ((1000+3000)/2 + (2000+4000)/2) / 2 = (2000 + 3000) / 2 = 2500
    assertThat(summary.avgAmount()).isEqualTo(2500L);
    assertThat(summary.items()).hasSize(2);
    assertThat(summary.items().get(0).speciality()).isEqualTo("근골격계");
    assertThat(summary.items().get(1).speciality()).isNull();
  }

  @Test
  @DisplayName("활성 리포트가 없으면 active_report·proposal_summary는 null, report_count는 그대로 채운다")
  void noActiveReportYieldsNullSections() {
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.empty());

    UserDashboardResponse result = service.getDashboard(userId);

    assertThat(result.activeReport()).isNull();
    assertThat(result.proposalSummary()).isNull();
    assertThat(result.reportCount()).isEqualTo(3L);
    assertThat(result.todos()).isNotNull();
    assertThat(result.todos().unreadChat()).isNull();
  }

  @Test
  @DisplayName("활성 리포트가 있어도 노출 제안이 없으면 proposal_count 0·proposal_summary null이다")
  void activeReportWithoutVisibleProposals() {
    UUID reportId = UUID.randomUUID();
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.of(
        new ActiveReportRow(reportId, "제목", AccidentType.TRAFFIC,
            ReportStatus.AWAITING_INSPECTION, LocalDateTime.now())));
    given(userDashboardRepository.findFirstReviewedAt(reportId)).willReturn(null);
    given(userDashboardRepository.findProposalItems(reportId)).willReturn(List.of());

    UserDashboardResponse result = service.getDashboard(userId);

    assertThat(result.activeReport().proposalCount()).isZero();
    assertThat(result.activeReport().firstReviewedAt()).isNull();
    assertThat(result.proposalSummary()).isNull();
  }

  @Test
  @DisplayName("제안 견적에 null이 섞이면 min·max는 null을 건너뛰고 avg는 min·max 둘 다 있는 제안만 집계한다")
  void proposalSummaryIsNullEstimateSafe() {
    UUID reportId = UUID.randomUUID();
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.of(
        new ActiveReportRow(reportId, "제목", AccidentType.DISABILITY,
            ReportStatus.COUNSELING, LocalDateTime.now())));
    given(userDashboardRepository.findFirstReviewedAt(reportId)).willReturn(LocalDateTime.now());
    given(userDashboardRepository.findProposalItems(reportId)).willReturn(List.of(
        proposalItem(null, 5000L, null),
        proposalItem(2000L, 4000L, List.of("교통사고"))));

    ProposalSummary summary = service.getDashboard(userId).proposalSummary();

    assertThat(summary.count()).isEqualTo(2);
    assertThat(summary.minAmount()).isEqualTo(2000L);
    assertThat(summary.maxAmount()).isEqualTo(5000L);
    assertThat(summary.avgAmount()).isEqualTo(3000L);
  }

  @Test
  @DisplayName("todos — 미확인 제안·검수완료 카운트와 대표 안읽음 채팅을 조립한다(항상 존재)")
  void assemblesTodosActionCenter() {
    UUID chatRoomId = UUID.randomUUID();
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.empty());
    given(userDashboardRepository.countUnreadProposals(userId)).willReturn(3L);
    given(userDashboardRepository.countUnreadReviewCompleted(userId)).willReturn(1L);
    given(userDashboardRepository.findTopUnreadChat(userId)).willReturn(Optional.of(
        new UnreadChatRow(chatRoomId, "김민준", "서류 검토가 끝났습니다.")));

    Todos todos = service.getDashboard(userId).todos();

    assertThat(todos.unreadProposalCount()).isEqualTo(3L);
    assertThat(todos.unreadReviewCompleteCount()).isEqualTo(1L);
    assertThat(todos.unreadChat().chatRoomId()).isEqualTo(chatRoomId);
    assertThat(todos.unreadChat().adjusterNickname()).isEqualTo("김민준");
    assertThat(todos.unreadChat().lastMessage()).isEqualTo("서류 검토가 끝났습니다.");
  }

  @Test
  @DisplayName("제안은 있으나 집계할 견적(min·max 둘 다)이 없으면 proposal_summary는 null이다")
  void proposalSummaryNullWhenNoFullEstimate() {
    UUID reportId = UUID.randomUUID();
    stubBaseline();
    given(userDashboardRepository.findLatestActiveReport(userId)).willReturn(Optional.of(
        new ActiveReportRow(reportId, "제목", AccidentType.TRAFFIC,
            ReportStatus.COUNSELING, LocalDateTime.now())));
    given(userDashboardRepository.findFirstReviewedAt(reportId)).willReturn(null);
    given(userDashboardRepository.findProposalItems(reportId)).willReturn(List.of(
        proposalItem(1000L, null, null),
        proposalItem(null, 2000L, null)));

    UserDashboardResponse result = service.getDashboard(userId);

    assertThat(result.activeReport().proposalCount()).isEqualTo(2L);
    assertThat(result.proposalSummary()).isNull();
  }

  /**
   * getDashboard가 항상 호출하는 report_count만 기본 스텁한다. findLatestActiveReport는 테스트마다
   * present/absent가 달라 각 테스트가 직접 스텁하거나(present) 기본 empty에 맡긴다.
   */
  private void stubBaseline() {
    given(reportRepository.countByUserId(userId)).willReturn(3L);
  }

  private static ProposalItemRow proposalItem(Long estimateMin, Long estimateMax, List<String> specialties) {
    return new ProposalItemRow(
        UUID.randomUUID(), UUID.randomUUID(), "사정사", 12, specialties, estimateMin, estimateMax);
  }
}
