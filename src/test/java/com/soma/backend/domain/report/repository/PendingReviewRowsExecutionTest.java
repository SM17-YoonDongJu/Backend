package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.HoldReason;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportHold;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;

/**
 * 검수대기 목록(findPendingReviewRows)과 건수(countPendingReviewRows)가 분리된 뒤에도 같은 조건을 쓰는지 고정한다.
 * 건수는 서비스가 캐시하므로 목록과 어긋나면 total_pages가 틀린다. 공유 test_db의 다른 데이터와 섞이지 않게
 * 시드 의뢰인 전원에게 테스트마다 고유한 지역을 주고 모든 조회에 그 지역 필터를 건다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PendingReviewRowsExecutionTest {

  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 50);

  @Autowired
  private ReportRepository reportRepository;
  @PersistenceContext
  private EntityManager entityManager;

  private String region;
  private UUID customerId;
  private UUID adjuster;

  @BeforeEach
  void setUp() {
    region = "R-" + UUID.randomUUID();
    User customer = User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, List.of(region));
    entityManager.persist(customer);
    customerId = customer.getId();
    adjuster = UUID.randomUUID();
  }

  @Test
  @DisplayName("보류·내 SENT·COUNSELING 검수는 목록과 건수에서 함께 빠지고, REJECTED·타인 검수·풀 밖 상태는 기준대로")
  void listAndCountShareMyQueueCondition() {
    UUID inspection = saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "RW-1");
    UUID adoption = saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "RW-2");
    UUID held = saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.OTHER, "RW-3");
    UUID mySent = saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "RW-4");
    UUID myRejected = saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "RW-5");
    UUID othersSent = saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.OTHER, "RW-6");
    saveReport(ReportStatus.COUNSELING, AccidentType.OTHER, "RW-7"); // 풀 밖 — 제외
    entityManager.persist(ReportHold.create(held, adjuster, HoldReason.SCHEDULE_CONFLICT, null));
    entityManager.persist(ReportReview.create(mySent, adjuster));
    persistReview(myRejected, adjuster, ReviewStatus.REJECTED);
    entityManager.persist(ReportReview.create(othersSent, UUID.randomUUID()));
    flushAndClear();

    List<UUID> ids = listIds(null, null);

    assertThat(ids).containsExactlyInAnyOrder(inspection, adoption, myRejected, othersSent);
    assertThat(reportRepository.countPendingReviewRows(null, null, region, adjuster)).isEqualTo(ids.size());
  }

  @Test
  @DisplayName("status·accidentType 필터 조합마다 건수가 목록 개수와 같다")
  void countMatchesListPerFilter() {
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.TRAFFIC, "RF-1");
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.FIRE, "RF-2");
    saveReport(ReportStatus.AWAITING_ADOPTION, AccidentType.TRAFFIC, "RF-3");
    flushAndClear();

    assertSameSize(null, null, 3);
    assertSameSize(ReportStatus.AWAITING_INSPECTION, null, 2);
    assertSameSize(null, AccidentType.TRAFFIC, 2);
    assertSameSize(ReportStatus.AWAITING_ADOPTION, AccidentType.TRAFFIC, 1);
    assertSameSize(ReportStatus.AWAITING_ADOPTION, AccidentType.FIRE, 0);
  }

  @Test
  @DisplayName("전문분야 매칭 유형이 상단에 오고, 정렬 인자(specialtyTypes)는 건수에 영향이 없다")
  void specialtySortDoesNotAffectCount() {
    UUID fire = saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.FIRE, "RS-1");
    UUID traffic = saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.TRAFFIC, "RS-2");
    flushAndClear();

    List<UUID> sorted = reportRepository.findPendingReviewRows(
        null, null, region, adjuster, Set.of(AccidentType.FIRE), FIRST_PAGE).stream()
        .map(PendingReviewRow::reportId).toList();

    assertThat(sorted).containsExactly(fire, traffic);
    assertThat(reportRepository.countPendingReviewRows(null, null, region, adjuster)).isEqualTo(2L);
  }

  @Test
  @DisplayName("전문분야 정렬 + created_at 동률이 섞여도 페이지를 끝까지 넘기면 순서대로 누락·중복 없이 모인다")
  void pagingThroughTiesHasNoGapOrDuplicate() {
    LocalDateTime t1 = LocalDateTime.of(2026, 10, 1, 10, 0);
    LocalDateTime t2 = t1.plusHours(1);
    List<UUID> all = new ArrayList<>();
    all.add(saveReportAt(AccidentType.FIRE, "RP-1", t1));
    all.add(saveReportAt(AccidentType.FIRE, "RP-2", t2));
    all.add(saveReportAt(AccidentType.FIRE, "RP-3", t2));
    all.add(saveReportAt(AccidentType.TRAFFIC, "RP-4", t2));
    all.add(saveReportAt(AccidentType.TRAFFIC, "RP-5", t2));
    all.add(saveReportAt(AccidentType.OTHER, "RP-6", t2));
    all.add(saveReportAt(AccidentType.TRAFFIC, "RP-7", t1));
    flushAndClear();

    List<PendingReviewRow> collected = new ArrayList<>();
    for (int page = 0; page < 10; page++) {
      List<PendingReviewRow> rows = reportRepository.findPendingReviewRows(
          null, null, region, adjuster, Set.of(AccidentType.FIRE), PageRequest.of(page, 2));
      if (rows.isEmpty()) {
        break;
      }
      collected.addAll(rows);
    }
    List<UUID> pagedIds = collected.stream().map(PendingReviewRow::reportId).toList();
    List<UUID> singlePageIds = reportRepository.findPendingReviewRows(
        null, null, region, adjuster, Set.of(AccidentType.FIRE), FIRST_PAGE).stream()
        .map(PendingReviewRow::reportId).toList();

    assertThat(pagedIds).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
    assertThat(pagedIds).containsExactlyElementsOf(singlePageIds);
    // 기대 순서를 구현과 독립적으로 만든다: 전문분야 → created_at desc → id asc(동률 타이브레이커).
    // PostgreSQL uuid 정렬은 부호 없는 바이트 순서라 Java UUID.compareTo(부호 있는 long 비교)가 아니라
    // 소문자 16진 문자열 사전순과 같다.
    List<UUID> expected = new ArrayList<>();
    expected.addAll(sortedByText(all.get(1), all.get(2)));
    expected.add(all.get(0));
    expected.addAll(sortedByText(all.get(3), all.get(4), all.get(5)));
    expected.add(all.get(6));
    assertThat(pagedIds).containsExactlyElementsOf(expected);
    // 전문분야(FIRE) 3건이 먼저, 각 묶음 안에서는 접수 최신순.
    assertThat(collected.subList(0, 3)).allMatch(row -> row.accidentType() == AccidentType.FIRE);
    assertThat(collected.subList(3, collected.size())).noneMatch(row -> row.accidentType() == AccidentType.FIRE);
    assertThat(collected.subList(0, 3)).extracting(PendingReviewRow::createdAt).containsExactly(t2, t2, t1);
    assertThat(collected.subList(3, collected.size())).extracting(PendingReviewRow::createdAt)
        .containsExactly(t2, t2, t2, t1);
  }

  @Test
  @DisplayName("ID 단계가 0건이면(마지막 페이지 너머·조건 불일치) 상세 조회 없이 빈 목록")
  void emptyIdsReturnEmptyList() {
    saveReport(ReportStatus.AWAITING_INSPECTION, AccidentType.FIRE, "RE-1");
    flushAndClear();

    assertThat(reportRepository.findPendingReviewRows(
        null, null, region, adjuster, Set.of(), PageRequest.of(1, 50))).isEmpty();
    assertThat(reportRepository.findPendingReviewRows(
        null, AccidentType.TRAFFIC, region, adjuster, Set.of(), FIRST_PAGE)).isEmpty();
  }

  private void assertSameSize(ReportStatus status, AccidentType accidentType, int expected) {
    assertThat(listIds(status, accidentType)).hasSize(expected);
    assertThat(reportRepository.countPendingReviewRows(status, accidentType, region, adjuster))
        .isEqualTo(expected);
  }

  private List<UUID> listIds(ReportStatus status, AccidentType accidentType) {
    return reportRepository.findPendingReviewRows(status, accidentType, region, adjuster, Set.of(), FIRST_PAGE)
        .stream().map(PendingReviewRow::reportId).toList();
  }

  private UUID saveReport(ReportStatus status, AccidentType accidentType, String caseNo) {
    Report report = Report.createPending(customerId, null, null, accidentType, "질문", caseNo + "-" + region);
    ReflectionTestUtils.setField(report, "status", status);
    return reportRepository.save(report).getId();
  }

  /** created_at은 감사 컬럼(updatable=false)이라 flush 후 네이티브 UPDATE로 동률·순서를 고정한다(테스트 전용). */
  private UUID saveReportAt(AccidentType accidentType, String caseNo, LocalDateTime createdAt) {
    UUID id = saveReport(ReportStatus.AWAITING_INSPECTION, accidentType, caseNo);
    entityManager.flush();
    entityManager.createNativeQuery("UPDATE reports SET created_at = :createdAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", id)
        .executeUpdate();
    return id;
  }

  private static List<UUID> sortedByText(UUID... ids) {
    return Arrays.stream(ids).sorted(Comparator.comparing(UUID::toString)).toList();
  }

  private void persistReview(UUID reportId, UUID adjusterId, ReviewStatus status) {
    ReportReview review = ReportReview.create(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "status", status);
    entityManager.persist(review);
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
