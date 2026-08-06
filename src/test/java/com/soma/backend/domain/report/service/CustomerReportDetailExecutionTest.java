package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 상세(GET /reports/{reportId})의 서비스+QueryDSL 실행 검증 — 실제 test_db에 시드를 넣고
 * ReportQueryService.getReportDetail을 태워 확정 배열 분기(ACCEPTED 있음=review, 없음=report)·검수 코멘트·
 * 검수 시각·담당 사정사(nickname·career)·쟁점 매핑(tags 빈배열)·confidence 대문자·인가(소유자/사정사/타인/부재)를
 * 확인한다. @Transactional로 각 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerReportDetailExecutionTest {

  @Autowired
  private ReportQueryService reportQueryService;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private ReportIssueRepository reportIssueRepository;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private AdjusterProfileRepository adjusterProfileRepository;
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("ACCEPTED 제안 없음 — 확정 배열은 report 원본, 코멘트·시각·사정사는 null, tags는 빈 배열, confidence는 대문자")
  void readsReportWithoutAcceptedReview() {
    UUID ownerId = UUID.randomUUID();
    Report report = newReport(ownerId, ReportStatus.AWAITING_INSPECTION, AccidentType.DISABILITY, "CRD-1");
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));
    ReflectionTestUtils.setField(report, "omittedSpecialContract", List.of("일상생활배상책임"));
    ReflectionTestUtils.setField(report, "basisTermsPrecedents", List.of("약관 제12조"));
    UUID reportId = reportRepository.save(report).getId();
    saveIssue(reportId, "장해등급 과소 산정 가능", "우측 슬관절 후유장해 등급이 낮게 산정됨", "CONFIRMED", null, null);
    flushAndClear();

    CustomerReportDetailResponse res = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    assertThat(res.reportId()).isEqualTo(reportId);
    assertThat(res.status()).isEqualTo("AWAITING_INSPECTION");
    assertThat(res.accidentType()).isEqualTo("disability");
    assertThat(res.treatment()).isEqualTo("3주 입원 후 통원");
    assertThat(res.claimedMinAmount()).isEqualTo(12_000_000);
    assertThat(res.claimedMaxAmount()).isEqualTo(18_000_000);
    assertThat(res.offeredAmount()).isEqualTo(8_500_000);
    assertThat(res.confidenceLevel()).isEqualTo("HIGH");
    assertThat(res.reportNo()).isEqualTo("CRD-1");
    // ACCEPTED 제안이 없으므로 확정 배열은 report 원본을 그대로 노출한다.
    assertThat(res.applicableGuarantees()).containsExactly("상해후유장해");
    assertThat(res.omittedSpecialContract()).containsExactly("일상생활배상책임");
    assertThat(res.basisTermsPrecedents()).containsExactly("약관 제12조");
    assertThat(res.reviewComment()).isNull();
    assertThat(res.reviewedAt()).isNull();
    assertThat(res.adjusterId()).isNull();
    assertThat(res.adjuster()).isNull();
    // 쟁점 매핑 — tags가 null이어도 빈 배열로 내린다(FE 계약).
    assertThat(res.issue()).hasSize(1);
    assertThat(res.issue().get(0).title()).isEqualTo("장해등급 과소 산정 가능");
    assertThat(res.issue().get(0).status()).isEqualTo("CONFIRMED");
    // 채택 리뷰 오버레이가 없으니 opinion은 AI description 폴백.
    assertThat(res.issue().get(0).opinion()).isEqualTo("우측 슬관절 후유장해 등급이 낮게 산정됨");
    assertThat(res.issue().get(0).tags()).isEmpty();
    assertThat(res.issue().get(0).impactAmount()).isNull();
  }

  @Test
  @DisplayName("ACCEPTED 제안 있음 — 확정 배열은 review 값, 코멘트·시각·담당 사정사(nickname·career 문자열)를 채운다")
  void readsReportWithAcceptedReviewAndAdjuster() {
    UUID ownerId = UUID.randomUUID();
    UUID adjusterId = saveAdjuster("박사정", 7);
    Report report = newReport(ownerId, ReportStatus.CLOSED, AccidentType.TRAFFIC, "CRD-2");
    ReflectionTestUtils.setField(report, "adjusterId", adjusterId);
    // report 원본 배열은 ACCEPTED review 값으로 대체돼야 한다(구분 가능한 마커값).
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("REPORT_보장"));
    ReflectionTestUtils.setField(report, "omittedSpecialContract", List.of("REPORT_특약"));
    ReflectionTestUtils.setField(report, "basisTermsPrecedents", List.of("REPORT_근거"));
    UUID reportId = reportRepository.save(report).getId();
    UUID aiIssueId =
        saveIssue(reportId, "과실비율 쟁점", "AI 초안 설명", "TRUSTED", List.of("교통사고", "과실"), 5_000_000);
    // 채택 리뷰 + 그 쟁점 오버레이(사정사 의견) → opinion은 adjuster_opinion 우선.
    saveAcceptedReviewWithOpinion(reportId, adjusterId, "검수 의견입니다",
        List.of("REVIEW_보장"), List.of("REVIEW_특약"), List.of("REVIEW_근거"),
        aiIssueId, "사정사 의견: 과실 40%로 재산정");
    // ACCEPTED만 반영되므로 형제 SENT 제안(다른 사정사)이 있어도 무시된다.
    saveReview(reportId, UUID.randomUUID(), ReviewStatus.SENT);
    flushAndClear();

    CustomerReportDetailResponse res = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    assertThat(res.status()).isEqualTo("MATCHED"); // CLOSED → MATCHED
    assertThat(res.applicableGuarantees()).containsExactly("REVIEW_보장");
    assertThat(res.omittedSpecialContract()).containsExactly("REVIEW_특약");
    assertThat(res.basisTermsPrecedents()).containsExactly("REVIEW_근거");
    assertThat(res.reviewComment()).isEqualTo("검수 의견입니다");
    assertThat(res.reviewedAt()).isNotNull();
    assertThat(res.adjusterId()).isEqualTo(adjusterId);
    assertThat(res.adjuster()).isNotNull();
    assertThat(res.adjuster().nickname()).isEqualTo("박사정");
    assertThat(res.adjuster().career()).isEqualTo(7);
    assertThat(res.issue()).hasSize(1);
    assertThat(res.issue().get(0).opinion()).isEqualTo("사정사 의견: 과실 40%로 재산정");
    assertThat(res.issue().get(0).status()).isEqualTo("TRUSTED");
    assertThat(res.issue().get(0).tags()).containsExactly("교통사고", "과실");
    assertThat(res.issue().get(0).impactAmount()).isEqualTo(5_000_000);
  }

  @Test
  @DisplayName("사정사(CERTIFICATED·UNCERTIFICATED)는 소유자가 아니어도 상세를 조회할 수 있다")
  void adjusterCanReadOthersReport() {
    UUID ownerId = UUID.randomUUID();
    UUID reportId =
        reportRepository.save(newReport(ownerId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "CRD-3"))
            .getId();
    flushAndClear();

    assertThat(reportQueryService.getReportDetail(UUID.randomUUID(), "CERTIFICATED_ADJUSTER", reportId).reportId())
        .isEqualTo(reportId);
    assertThat(reportQueryService.getReportDetail(UUID.randomUUID(), "UNCERTIFICATED_ADJUSTER", reportId).reportId())
        .isEqualTo(reportId);
  }

  @Test
  @DisplayName("소유자도 사정사도 아닌 USER면 FORBIDDEN(403)")
  void strangerUserForbidden() {
    UUID ownerId = UUID.randomUUID();
    UUID reportId =
        reportRepository.save(newReport(ownerId, ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "CRD-4"))
            .getId();
    flushAndClear();

    assertThatThrownBy(() -> reportQueryService.getReportDetail(UUID.randomUUID(), "USER", reportId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("존재하지 않는 리포트면 REPORT_NOT_FOUND(404)")
  void missingReportNotFound() {
    assertThatThrownBy(() -> reportQueryService.getReportDetail(UUID.randomUUID(), "USER", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  // --- 시드 헬퍼 ---

  private Report newReport(UUID userId, ReportStatus status, AccidentType accidentType, String caseNo) {
    Report report = Report.createPending(userId, null, null, accidentType, "후유장해 청구가 가능한가요?", caseNo);
    ReflectionTestUtils.setField(report, "status", status);
    ReflectionTestUtils.setField(report, "treatment", "3주 입원 후 통원");
    ReflectionTestUtils.setField(report, "confidenceLevel", "high");
    ReflectionTestUtils.setField(report, "claimedMinAmount", 12_000_000);
    ReflectionTestUtils.setField(report, "claimedMaxAmount", 18_000_000);
    ReflectionTestUtils.setField(report, "offeredAmount", 8_500_000);
    return report;
  }

  private UUID saveIssue(
      UUID reportId, String title, String description, String aiStatus, List<String> tags, Integer impactAmount) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "reportId", reportId);
    ReflectionTestUtils.setField(issue, "title", title);
    ReflectionTestUtils.setField(issue, "description", description);
    ReflectionTestUtils.setField(issue, "aiStatus", aiStatus);
    ReflectionTestUtils.setField(issue, "tags", tags);
    ReflectionTestUtils.setField(issue, "impactAmount", impactAmount);
    return reportIssueRepository.save(issue).getId();
  }

  private void saveAcceptedReviewWithOpinion(UUID reportId, UUID adjusterId, String review,
      List<String> guarantees, List<String> special, List<String> basis,
      UUID reportIssueId, String adjusterOpinion) {
    ReportReview reportReview = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(reportReview, "status", ReviewStatus.ACCEPTED);
    ReflectionTestUtils.setField(reportReview, "review", review);
    ReflectionTestUtils.setField(reportReview, "applicableGuarantees", guarantees);
    ReflectionTestUtils.setField(reportReview, "omittedSpecialContract", special);
    ReflectionTestUtils.setField(reportReview, "basisTermsPrecedents", basis);
    ReportReviewIssue overlay = BeanUtils.instantiateClass(ReportReviewIssue.class);
    ReflectionTestUtils.setField(overlay, "reportIssueId", reportIssueId);
    ReflectionTestUtils.setField(overlay, "adjusterOpinion", adjusterOpinion);
    ReflectionTestUtils.setField(overlay, "reviewStatus", IssueReviewStatus.MODIFIED);
    reportReview.getIssues().add(overlay);
    reportReviewRepository.save(reportReview);
  }

  private void saveReview(UUID reportId, UUID adjusterId, ReviewStatus status) {
    ReportReview reportReview = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(reportReview, "status", status);
    reportReviewRepository.save(reportReview);
  }

  private UUID saveAdjuster(String nickname, Integer career) {
    UUID adjusterId = userRepository.save(
        User.create(nickname, LocalDate.of(1985, 3, 3), "M", null, Role.CERTIFICATED_ADJUSTER, null)).getId();
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "userId", adjusterId);
    ReflectionTestUtils.setField(profile, "career", career);
    adjusterProfileRepository.save(profile);
    return adjusterId;
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
