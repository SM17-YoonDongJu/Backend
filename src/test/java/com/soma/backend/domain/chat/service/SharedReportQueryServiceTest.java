package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.SharedReportResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅방 공유 리포트 조회(design.md §5) 단위 테스트. 인가 순서(방 미존재→멤버→reportReviewId null→
 * review→report)와 쟁점 resolve/필터(overlay 우선 fallback, EXCLUDED·미검수 스킵, ADDED tags 빈 배열)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SharedReportQueryServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;

  @InjectMocks
  private SharedReportQueryService service;

  private UUID me;
  private UUID adjusterId;
  private UUID chatRoomId;
  private UUID reportId;
  private UUID reviewId;

  @BeforeEach
  void setUp() {
    me = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    chatRoomId = UUID.randomUUID();
    reportId = UUID.randomUUID();
    reviewId = UUID.randomUUID();
  }

  @Test
  @DisplayName("정상: 방 참여자면 리포트·사정사·쟁점을 매핑하고 overlay 우선 resolve(제목 override·설명 fallback)한다")
  void getSharedReport_success_mapsAllFieldsAndResolvesOverlay() {
    // Given
    ChatRoom chatRoom = memberRoom();
    Report report = report();
    ReportReview review = review();
    ReportIssue aiIssue = aiIssue("AI제목", "AI설명", 1_000_000L, List.of("교통", "후유장해"));
    // overlay: 제목은 override, 설명은 null(→AI fallback), 금액은 override
    review.getIssues().add(overlay(aiIssue.getId(), IssueReviewStatus.ACCEPTED,
        "사정사제목", null, 2_000_000L, "인정합니다"));
    review.getIssues().add(overlay(null, IssueReviewStatus.ADDED,
        "신규쟁점", "신규설명", 3_000_000L, "추가 의견"));
    givenPipeline(chatRoom, review, report, List.of(aiIssue), adjusterProfile());

    // When
    SharedReportResponse response = service.getSharedReport(me, chatRoomId);

    // Then — 상단·메타
    assertThat(response.chatRoomId()).isEqualTo(chatRoomId);
    assertThat(response.reportId()).isEqualTo(reportId);
    assertThat(response.proposalId()).isEqualTo(reviewId);
    assertThat(response.caseNo()).isEqualTo("20260520-017");
    assertThat(response.accidentType()).isEqualTo("traffic");
    assertThat(response.title()).isEqualTo("교통사고 후유장해");
    assertThat(response.reportStatus()).isEqualTo("COUNSELING");
    assertThat(response.reviewStatus()).isEqualTo("SENT");
    assertThat(response.reportUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 20, 9, 0));
    assertThat(response.submittedAt()).isEqualTo(LocalDateTime.of(2026, 5, 22, 10, 0));
    assertThat(response.summary()).isEqualTo("검수 요약 인용문");
    assertThat(response.offeredAmount()).isEqualTo(5_000_000L);
    assertThat(response.estimate().min()).isEqualTo(12_000_000L);
    assertThat(response.estimate().max()).isEqualTo(18_000_000L);
    assertThat(response.applicableGuarantees()).containsExactly("보장A");
    assertThat(response.omittedSpecialContract()).containsExactly("특약B");
    assertThat(response.basisTermsPrecedents()).containsExactly("근거C");

    // Then — 사정사
    assertThat(response.adjuster().adjusterId()).isEqualTo(adjusterId);
    assertThat(response.adjuster().name()).isEqualTo("김사정");
    assertThat(response.adjuster().career()).isEqualTo(18);
    assertThat(response.adjuster().specialties()).containsExactly("후유장해", "교통사고");

    // Then — 쟁점(AI 매칭분 → ADDED 순서), overlay 우선 resolve
    assertThat(response.issueCount()).isEqualTo(2);
    SharedReportResponse.Issue matched = response.issues().get(0);
    assertThat(matched.issueId()).isEqualTo(aiIssue.getId());
    assertThat(matched.reviewIssueId()).isNotNull();
    assertThat(matched.title()).isEqualTo("사정사제목");
    assertThat(matched.description()).isEqualTo("AI설명");
    assertThat(matched.impactAmount()).isEqualTo(2_000_000L);
    assertThat(matched.adjusterOpinion()).isEqualTo("인정합니다");
    assertThat(matched.reviewStatus()).isEqualTo("ACCEPTED");
    assertThat(matched.tags()).containsExactly("교통", "후유장해");

    SharedReportResponse.Issue added = response.issues().get(1);
    assertThat(added.issueId()).isNull();
    assertThat(added.title()).isEqualTo("신규쟁점");
    assertThat(added.reviewStatus()).isEqualTo("ADDED");
    assertThat(added.tags()).isEmpty();
  }

  @Test
  @DisplayName("쟁점: EXCLUDED overlay가 달린 AI 쟁점은 응답에서 제외된다")
  void getSharedReport_excludedOverlay_skipped() {
    // Given
    ReportReview review = review();
    ReportIssue kept = aiIssue("유지쟁점", "설명", 1_000L, List.of("tag"));
    ReportIssue excluded = aiIssue("제외쟁점", "설명", 2_000L, List.of("tag"));
    review.getIssues().add(overlay(kept.getId(), IssueReviewStatus.ACCEPTED, null, null, null, "인정"));
    review.getIssues().add(overlay(excluded.getId(), IssueReviewStatus.EXCLUDED, null, null, null, "제외"));
    givenPipeline(memberRoom(), review, report(), List.of(kept, excluded), adjusterProfile());

    // When
    SharedReportResponse response = service.getSharedReport(me, chatRoomId);

    // Then
    assertThat(response.issueCount()).isEqualTo(1);
    assertThat(response.issues()).hasSize(1);
    assertThat(response.issues().get(0).issueId()).isEqualTo(kept.getId());
  }

  @Test
  @DisplayName("쟁점: ADDED(사정사 신규) 쟁점은 issueId=null이고 tags는 빈 배열이다")
  void getSharedReport_addedIssue_hasNullIssueIdAndEmptyTags() {
    // Given
    ReportReview review = review();
    review.getIssues().add(overlay(null, IssueReviewStatus.ADDED, "신규", "신규설명", 4_000L, "추가"));
    givenPipeline(memberRoom(), review, report(), List.of(), adjusterProfile());

    // When
    SharedReportResponse response = service.getSharedReport(me, chatRoomId);

    // Then
    assertThat(response.issueCount()).isEqualTo(1);
    SharedReportResponse.Issue added = response.issues().get(0);
    assertThat(added.issueId()).isNull();
    assertThat(added.title()).isEqualTo("신규");
    assertThat(added.reviewStatus()).isEqualTo("ADDED");
    assertThat(added.tags()).isEmpty();
  }

  @Test
  @DisplayName("쟁점: overlay(검수 의견)가 없는 AI 쟁점은 응답에서 스킵된다")
  void getSharedReport_aiIssueWithoutOverlay_skipped() {
    // Given
    ReportReview review = review();
    ReportIssue reviewed = aiIssue("검수됨", "설명", 1_000L, List.of("tag"));
    ReportIssue notReviewed = aiIssue("미검수", "설명", 2_000L, List.of("tag"));
    review.getIssues().add(overlay(reviewed.getId(), IssueReviewStatus.MODIFIED, null, null, null, "수정"));
    givenPipeline(memberRoom(), review, report(), List.of(reviewed, notReviewed), adjusterProfile());

    // When
    SharedReportResponse response = service.getSharedReport(me, chatRoomId);

    // Then
    assertThat(response.issueCount()).isEqualTo(1);
    assertThat(response.issues().get(0).issueId()).isEqualTo(reviewed.getId());
    assertThat(response.issues().get(0).reviewStatus()).isEqualTo("MODIFIED");
  }

  @Test
  @DisplayName("사정사 프로필이 없으면 adjuster의 name·career·specialties는 null(방어)이고 adjusterId는 유지된다")
  void getSharedReport_noAdjusterProfile_returnsNullAdjusterFields() {
    // Given
    ReportReview review = review();
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(memberRoom()));
    given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(review));
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report()));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(adjusterProfileRepository.findByUserId(adjusterId)).willReturn(Optional.empty());

    // When
    SharedReportResponse response = service.getSharedReport(me, chatRoomId);

    // Then
    assertThat(response.adjuster().adjusterId()).isEqualTo(adjusterId);
    assertThat(response.adjuster().name()).isNull();
    assertThat(response.adjuster().career()).isNull();
    assertThat(response.adjuster().specialties()).isNull();
  }

  @Test
  @DisplayName("인가: chatRoom이 없으면 CHAT_ROOM_NOT_FOUND(404)")
  void getSharedReport_roomNotFound_throwsChatRoomNotFound() {
    // Given
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> service.getSharedReport(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  @Test
  @DisplayName("인가: 방 참여자가 아니면 CHAT_NOT_A_MEMBER(403)")
  void getSharedReport_notMember_throwsNotAMember() {
    // Given — me가 user·adjuster 어느 쪽도 아닌 방
    ChatRoom room = ChatRoomFixture.withId(
        chatRoomId, UUID.randomUUID(), UUID.randomUUID(), reportId, reviewId, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(room));

    // When & Then
    assertThatThrownBy(() -> service.getSharedReport(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER);
  }

  @Test
  @DisplayName("인가: 검색 방(reportReviewId==null)이면 PROPOSAL_NOT_FOUND(404)")
  void getSharedReport_searchRoom_throwsProposalNotFound() {
    // Given — reportReviewId 없는 검색 방
    ChatRoom searchRoom = ChatRoomFixture.withId(
        chatRoomId, me, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(searchRoom));

    // When & Then
    assertThatThrownBy(() -> service.getSharedReport(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PROPOSAL_NOT_FOUND);
  }

  @Test
  @DisplayName("인가: ReportReview가 없으면 PROPOSAL_NOT_FOUND(404)")
  void getSharedReport_reviewNotFound_throwsProposalNotFound() {
    // Given
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(memberRoom()));
    given(reportReviewRepository.findById(reviewId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> service.getSharedReport(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PROPOSAL_NOT_FOUND);
  }

  @Test
  @DisplayName("인가: Report가 없으면 REPORT_NOT_FOUND(404)")
  void getSharedReport_reportNotFound_throwsReportNotFound() {
    // Given
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(memberRoom()));
    given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(review()));
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> service.getSharedReport(me, chatRoomId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  private void givenPipeline(
      ChatRoom chatRoom, ReportReview review, Report report,
      List<ReportIssue> aiIssues, AdjusterProfile adjusterProfile) {
    given(chatRoomRepository.findById(chatRoomId)).willReturn(Optional.of(chatRoom));
    given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(review));
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(aiIssues);
    given(adjusterProfileRepository.findByUserId(adjusterId)).willReturn(Optional.of(adjusterProfile));
  }

  private ChatRoom memberRoom() {
    return ChatRoomFixture.withId(chatRoomId, me, adjusterId, reportId, reviewId, ChatRoomStatus.ACTIVE);
  }

  private Report report() {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "caseNo", "20260520-017");
    ReflectionTestUtils.setField(report, "title", "교통사고 후유장해");
    ReflectionTestUtils.setField(report, "accidentType", AccidentType.TRAFFIC);
    ReflectionTestUtils.setField(report, "status", ReportStatus.COUNSELING);
    ReflectionTestUtils.setField(report, "offeredAmount", 5_000_000L);
    ReflectionTestUtils.setField(report, "updatedAt", LocalDateTime.of(2026, 5, 20, 9, 0));
    return report;
  }

  private ReportReview review() {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", reviewId);
    ReflectionTestUtils.setField(review, "review", "검수 요약 인용문");
    ReflectionTestUtils.setField(review, "estimateMinAmount", 12_000_000L);
    ReflectionTestUtils.setField(review, "estimateMaxAmount", 18_000_000L);
    ReflectionTestUtils.setField(review, "applicableGuarantees", List.of("보장A"));
    ReflectionTestUtils.setField(review, "omittedSpecialContract", List.of("특약B"));
    ReflectionTestUtils.setField(review, "basisTermsPrecedents", List.of("근거C"));
    ReflectionTestUtils.setField(review, "createdAt", LocalDateTime.of(2026, 5, 22, 10, 0));
    return review;
  }

  private ReportIssue aiIssue(String title, String description, Long impactAmount, List<String> tags) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(issue, "reportId", reportId);
    ReflectionTestUtils.setField(issue, "title", title);
    ReflectionTestUtils.setField(issue, "description", description);
    ReflectionTestUtils.setField(issue, "impactAmount", impactAmount);
    ReflectionTestUtils.setField(issue, "tags", tags);
    return issue;
  }

  private ReportReviewIssue overlay(UUID reportIssueId, IssueReviewStatus status,
      String title, String description, Long impactAmount, String opinion) {
    ReportReviewIssue overlay = new ReportReviewIssue(
        reportIssueId, title, description, impactAmount, status, opinion, null, null);
    ReflectionTestUtils.setField(overlay, "id", UUID.randomUUID());
    return overlay;
  }

  private AdjusterProfile adjusterProfile() {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", adjusterId);
    ReflectionTestUtils.setField(profile, "name", "김사정");
    ReflectionTestUtils.setField(profile, "career", 18);
    ReflectionTestUtils.setField(profile, "specialties", List.of("후유장해", "교통사고"));
    return profile;
  }
}
