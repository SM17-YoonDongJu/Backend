package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewStatusRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.PendingReviewCountCache;

/** API#1(요약)·API#2(목록) 조회 유스케이스 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class PendingReviewQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;
  @Mock
  private PendingReviewCountCache pendingReviewCountCache;

  @InjectMocks
  private PendingReviewQueryService service;

  @Test
  @DisplayName("summary는 보류 제외 pending/due-soon 카운트를 반환하고 due-soon 기준은 now - 6일(SLA 7 - 여유 1)")
  void summaryUsesSlaThreshold() {
    UUID adjusterId = UUID.randomUUID();
    given(reportRepository.countPendingNotHeldBy(adjusterId)).willReturn(9L);
    given(reportRepository.countDueSoonNotHeldBy(any(LocalDateTime.class), eq(adjusterId))).willReturn(3L);
    given(reportReviewRepository.countInProgressByAdjusterId(adjusterId)).willReturn(4L);
    given(adjusterProfileRepository.findByUserId(adjusterId))
        .willReturn(Optional.of(adjusterProfile(List.of("교통사고"))));
    given(reportRepository.countPendingByAccidentTypeInNotHeldBy(any(), eq(adjusterId))).willReturn(5L);

    LocalDateTime before = LocalDateTime.now().minusDays(6);
    PendingReviewSummaryResponse summary = service.getSummary(adjusterId);
    LocalDateTime after = LocalDateTime.now().minusDays(6);

    assertThat(summary.pendingCount()).isEqualTo(9L);
    assertThat(summary.dueSoonCount()).isEqualTo(3L);
    assertThat(summary.inProgressCount()).isEqualTo(4L);
    assertThat(summary.specialtyMatchCount()).isEqualTo(5L);

    ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reportRepository).countDueSoonNotHeldBy(captor.capture(), eq(adjusterId));
    assertThat(captor.getValue()).isBetween(before, after);
  }

  private AdjusterProfile adjusterProfile(List<String> specialties) {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "specialties", specialties);
    return profile;
  }

  @Test
  @DisplayName("목록 아이템의 offer_headroom은 claimed_max - offered(음수 허용), null은 0 처리")
  void listMapsDerivedFields() {
    UUID reportId = UUID.randomUUID();
    PendingReviewRow row = row(reportId, 1000, 5000, 8000, 2L, true);
    Pageable pageable = PageRequest.of(0, 20);
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(reportId), any(), eq(pageable)))
        .willReturn(List.of(row));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any()))
        .willReturn(List.of(new ReportReviewStatusRow(reportId, ReviewStatus.SENT)));

    PendingReviewListResponse result =
        service.getPendingReviewList(null, null, null, reportId, pageable);

    PendingReviewListResponse.Item item = result.list().get(0);
    assertThat(item.caseId()).isEqualTo("CASE-1");
    assertThat(item.region()).isEqualTo("서울");
    assertThat(item.offerHeadroom()).isEqualTo(-3000L);
    assertThat(item.issueCount()).isEqualTo(2L);
    assertThat(item.held()).isTrue();
    assertThat(item.reportReviewStatus()).isEqualTo("SENT");
    assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026, 5, 31, 9, 0));
    assertThat(result.pagination().totalElements()).isEqualTo(1L);
    assertThat(result.pagination().page()).isEqualTo(1);
    assertThat(result.pagination().hasNext()).isFalse();
  }

  @Test
  @DisplayName("null 파생값(issueCount/held)은 0/false로 안전 처리")
  void listNullSafe() {
    UUID adjusterId = UUID.randomUUID();
    PendingReviewRow row = row(UUID.randomUUID(), null, null, null, null, null);
    Pageable pageable = PageRequest.of(0, 20);
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of(row));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());

    PendingReviewListResponse.Item item =
        service.getPendingReviewList(null, null, null, adjusterId, pageable).list().get(0);

    assertThat(item.offerHeadroom()).isZero();
    assertThat(item.issueCount()).isZero();
    assertThat(item.held()).isFalse();
    assertThat(item.reportReviewStatus()).isNull();
  }

  @Test
  @DisplayName("목록 조회는 사정사 전문분야와 매칭되는 accident_type 집합을 정렬용으로 리포지토리에 넘긴다")
  void listPassesSpecialtyTypesForSorting() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    given(adjusterProfileRepository.findByUserId(adjusterId))
        .willReturn(Optional.of(adjusterProfile(List.of("교통사고", "장해"))));
    given(reportRepository.findPendingReviewRows(
        isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of());

    service.getPendingReviewList(null, null, null, adjusterId, pageable);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<AccidentType>> captor = ArgumentCaptor.forClass(Set.class);
    verify(reportRepository).findPendingReviewRows(
        isNull(), isNull(), isNull(), eq(adjusterId), captor.capture(), eq(pageable));
    assertThat(captor.getValue()).containsExactlyInAnyOrder(AccidentType.TRAFFIC, AccidentType.DISABILITY);
  }

  @Test
  @DisplayName("첫 페이지가 size보다 적게 채워지면 count를 세지 않는다(캐시·DB 모두 미호출)")
  void listSkipsCountWhenFirstPageIsPartial() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of(row(UUID.randomUUID(), null, null, null, null, null)));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());

    PendingReviewListResponse result = service.getPendingReviewList(null, null, null, adjusterId, pageable);

    assertThat(result.pagination().totalElements()).isEqualTo(1L);
    verify(pendingReviewCountCache, never()).getOrLoad(any(), any(), any());
    verify(reportRepository, never()).countPendingReviewRows(any(), any(), any(), any());
  }

  @Test
  @DisplayName("캐시 히트면 count 리포지토리를 호출하지 않고 total_pages를 캐시 값으로 계산한다")
  void listUsesCachedCount() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 1);
    given(reportRepository.findPendingReviewRows(
        eq(ReportStatus.AWAITING_INSPECTION), isNull(), eq("서울"), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of(row(UUID.randomUUID(), null, null, null, null, null)));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());
    given(pendingReviewCountCache.getOrLoad(eq(adjusterId), eq("v:AWAITING_INSPECTION:-:v:서울"), any()))
        .willReturn(42L);

    PendingReviewListResponse result =
        service.getPendingReviewList("AWAITING_INSPECTION", null, "서울", adjusterId, pageable);

    assertThat(result.pagination().totalElements()).isEqualTo(42L);
    assertThat(result.pagination().totalPages()).isEqualTo(42);
    assertThat(result.pagination().hasNext()).isTrue();
    verify(reportRepository, never()).countPendingReviewRows(any(), any(), any(), any());
  }

  @Test
  @DisplayName("캐시 미스면 loader가 목록과 같은 필터로 count 리포지토리를 호출한다")
  void listLoadsCountOnCacheMiss() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 1);
    given(reportRepository.findPendingReviewRows(
        isNull(), eq(AccidentType.TRAFFIC), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of(row(UUID.randomUUID(), null, null, null, null, null)));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());
    given(pendingReviewCountCache.getOrLoad(eq(adjusterId), eq("-:v:TRAFFIC:-"), any()))
        .willAnswer(inv -> inv.<LongSupplier>getArgument(2).getAsLong());
    given(reportRepository.countPendingReviewRows(null, AccidentType.TRAFFIC, null, adjusterId)).willReturn(3L);

    PendingReviewListResponse result = service.getPendingReviewList(null, "traffic", null, adjusterId, pageable);

    assertThat(result.pagination().totalElements()).isEqualTo(3L);
    assertThat(result.pagination().totalPages()).isEqualTo(3);
  }

  @Test
  @DisplayName("마지막 페이지가 부분적으로 채워지면 count 없이 offset+content로 total을 계산한다(has_next=false)")
  void listLastPartialPageSkipsCount() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(2, 10); // 3페이지(1-based), 21~30번째
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of(row(UUID.randomUUID(), null, null, null, null, null),
            row(UUID.randomUUID(), null, null, null, null, null)));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());

    PendingReviewListResponse result = service.getPendingReviewList(null, null, null, adjusterId, pageable);

    assertThat(result.pagination().page()).isEqualTo(3);
    assertThat(result.pagination().totalElements()).isEqualTo(22L);
    assertThat(result.pagination().totalPages()).isEqualTo(3);
    assertThat(result.pagination().hasNext()).isFalse();
    verify(pendingReviewCountCache, never()).getOrLoad(any(), any(), any());
  }

  @Test
  @DisplayName("범위를 넘는 페이지(빈 content)는 count를 조회해 기존처럼 total_pages를 돌려준다(has_next=false)")
  void listOutOfRangePageStillReportsTotal() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(9, 10);
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of());
    given(pendingReviewCountCache.getOrLoad(eq(adjusterId), eq("-:-:-"), any())).willReturn(15L);

    PendingReviewListResponse result = service.getPendingReviewList(null, null, null, adjusterId, pageable);

    assertThat(result.list()).isEmpty();
    assertThat(result.pagination().totalElements()).isEqualTo(15L);
    assertThat(result.pagination().totalPages()).isEqualTo(2);
    assertThat(result.pagination().hasNext()).isFalse();
  }

  @Test
  @DisplayName("빈 결과 첫 페이지는 count 없이 total 0, total_pages 0")
  void listEmptyFirstPage() {
    UUID adjusterId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 10);
    given(reportRepository.findPendingReviewRows(isNull(), isNull(), isNull(), eq(adjusterId), any(), eq(pageable)))
        .willReturn(List.of());

    PendingReviewListResponse result = service.getPendingReviewList(null, null, null, adjusterId, pageable);

    assertThat(result.pagination().totalElements()).isZero();
    assertThat(result.pagination().totalPages()).isZero();
    assertThat(result.pagination().hasNext()).isFalse();
    verify(pendingReviewCountCache, never()).getOrLoad(any(), any(), any());
  }

  @Test
  @DisplayName("잘못된 status 필터 값은 조용한 빈 결과 대신 VALIDATION_ERROR(400)로 거른다")
  void invalidStatusRejected() {
    Pageable pageable = PageRequest.of(0, 20);
    assertThatThrownBy(
        () -> service.getPendingReviewList("NOT_A_STATUS", null, null, UUID.randomUUID(), pageable))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("잘못된 accidentType 필터 값(구 한글 값 등)은 VALIDATION_ERROR(400)로 거른다")
  void invalidAccidentTypeRejected() {
    Pageable pageable = PageRequest.of(0, 20);
    assertThatThrownBy(
        () -> service.getPendingReviewList(null, "질병", null, UUID.randomUUID(), pageable))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("count 필터 키는 status:accidentType:region 순서, 값은 'v:' 접두어·null은 '-'")
  void countFilterKeyFormat() {
    assertThat(PendingReviewQueryService.countFilterKey(null, null, null)).isEqualTo("-:-:-");
    assertThat(PendingReviewQueryService.countFilterKey(ReportStatus.AWAITING_ADOPTION, AccidentType.FIRE, "부산"))
        .isEqualTo("v:AWAITING_ADOPTION:v:FIRE:v:부산");
  }

  @Test
  @DisplayName("region 값 '-'는 지역 필터 없음(null)과 다른 필드가 된다 — 서로의 count를 덮어쓰지 않는다")
  void countFilterKeyDistinguishesDashRegionFromNull() {
    assertThat(PendingReviewQueryService.countFilterKey(null, null, "-"))
        .isNotEqualTo(PendingReviewQueryService.countFilterKey(null, null, null));
    assertThat(PendingReviewQueryService.countFilterKey(null, null, ""))
        .isNotEqualTo(PendingReviewQueryService.countFilterKey(null, null, null));
  }

  private static PendingReviewRow row(
      UUID reportId, Integer claimedMin, Integer claimedMax, Integer offered, Long issueCount, Boolean held) {
    return new PendingReviewRow(
        reportId, "CASE-1", "제목", AccidentType.DISABILITY, List.of("서울"), ReportStatus.AWAITING_INSPECTION,
        claimedMin, claimedMax, offered, issueCount, held, LocalDateTime.of(2026, 5, 31, 9, 0));
  }
}
