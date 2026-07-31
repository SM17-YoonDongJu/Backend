package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewStatusRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#1(요약)·API#2(목록) 조회 전용 유스케이스(CQRS). Aggregate 로딩을 우회해 파생 필드를 조회한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingReviewQueryService {

  /** 검수 SLA(접수 후 검수 완료 기한). 기획 미확정 상태의 잠정값 — 리더 확정 시 조정 필요. */
  private static final long SLA_DAYS = 7L;

  /** 마감 임박으로 간주하는 SLA 잔여 여유 기간. SLA_DAYS 이내 이 값만큼 남았으면 due_soon. */
  private static final long DUE_SOON_WINDOW_DAYS = 1L;

  /** accident_type → 사정사 전문분야(specialties) 매칭용 대표 키워드. specialty가 이 키워드를 포함하면 매칭(OTHER 제외). */
  private static final Map<AccidentType, String> SPECIALTY_MATCH_KEYWORD = Map.of(
      AccidentType.MEDICAL_INDEMNITY, "실손",
      AccidentType.TRAFFIC, "교통사고",
      AccidentType.DISABILITY, "장해",
      AccidentType.CANCER_DIAGNOSIS, "암",
      AccidentType.FIRE, "화재",
      AccidentType.LIABILITY, "배상");

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final AdjusterProfileRepository adjusterProfileRepository;

  public PendingReviewSummaryResponse getSummary(UUID adjusterId) {
    // 검수대기 풀 카운트는 요청 사정사가 보류한 건을 제외한다(보류 = 내 대기열에서 제외). 마감임박·전문분야
    // 매칭도 같은 풀의 부분집합이라 보류 제외 축을 맞춘다. inProgressCount는 내 검수 상태 축이라 보류와 무관.
    long pendingCount = reportRepository.countPendingNotHeldBy(adjusterId);
    LocalDateTime dueSoonThreshold = LocalDateTime.now().minusDays(SLA_DAYS - DUE_SOON_WINDOW_DAYS);
    long dueSoonCount = reportRepository.countDueSoonNotHeldBy(dueSoonThreshold, adjusterId);
    long inProgressCount = reportReviewRepository.countInProgressByAdjusterId(adjusterId);
    long specialtyMatchCount = countSpecialtyMatch(adjusterId);
    return new PendingReviewSummaryResponse(pendingCount, dueSoonCount, inProgressCount, specialtyMatchCount);
  }

  /**
   * 검수 대기 풀(AWAITING_INSPECTION + AWAITING_ADOPTION) 중 요청 사정사의 전문분야(specialties)와
   * accident_type이 매칭되는 건수. specialties는 자유 텍스트라 accident_type별 대표 키워드 포함 여부로
   * 매칭한다(전문분야 없거나 매칭 없으면 0).
   */
  private long countSpecialtyMatch(UUID adjusterId) {
    Set<AccidentType> matched = matchedAccidentTypes(loadSpecialties(adjusterId));
    if (matched.isEmpty()) {
      return 0L;
    }
    return reportRepository.countPendingByAccidentTypeInNotHeldBy(matched, adjusterId);
  }

  /** 요청 사정사의 전문분야(자유 텍스트 리스트). 프로필이 없거나 미입력이면 null. */
  private List<String> loadSpecialties(UUID adjusterId) {
    return adjusterProfileRepository.findByUserId(adjusterId)
        .map(AdjusterProfile::getSpecialties)
        .orElse(null);
  }

  private Set<AccidentType> matchedAccidentTypes(List<String> specialties) {
    if (specialties == null || specialties.isEmpty()) {
      return Set.of();
    }
    Set<AccidentType> matched = EnumSet.noneOf(AccidentType.class);
    SPECIALTY_MATCH_KEYWORD.forEach((type, keyword) -> {
      if (specialties.stream().anyMatch(specialty -> specialty != null && specialty.contains(keyword))) {
        matched.add(type);
      }
    });
    return matched;
  }

  public PendingReviewListResponse getPendingReviewList(
      String status, String accidentType, String region, UUID adjusterId, Pageable pageable) {
    ReportStatus statusFilter = parseStatus(status);
    AccidentType accidentTypeFilter = parseAccidentType(accidentType);
    // 전문분야 우선 정렬: 요청 사정사의 전문분야와 매칭되는 accident_type을 상단에 올린다(그 안에서는 접수 최신순).
    Set<AccidentType> specialtyTypes = matchedAccidentTypes(loadSpecialties(adjusterId));
    Page<PendingReviewRow> rows = reportRepository.findPendingReviewRows(
        statusFilter, accidentTypeFilter, region, adjusterId, specialtyTypes, pageable);
    Map<UUID, String> reviewStatusByReportId = loadOwnReviewStatuses(adjusterId, rows);
    return PendingReviewListResponse.from(rows, reviewStatusByReportId);
  }

  /**
   * 목록 페이지의 리포트들에 대해 요청 사정사 본인의 검수 상태(REPORT_REVIEWS.status)를 report_id→status로
   * 한 번에 조회한다. 본인 검수가 없는 리포트는 맵에 없어 응답에서 null이 된다.
   */
  private Map<UUID, String> loadOwnReviewStatuses(UUID adjusterId, Page<PendingReviewRow> rows) {
    List<UUID> reportIds = rows.getContent().stream().map(PendingReviewRow::reportId).toList();
    if (reportIds.isEmpty()) {
      return Map.of();
    }
    return reportReviewRepository.findAdjusterReviewStatuses(adjusterId, reportIds).stream()
        .collect(Collectors.toMap(ReportReviewStatusRow::reportId, row -> row.status().name()));
  }

  /** 잘못된 status 필터 값은 조용한 빈 결과 대신 400으로 거른다. 빈 값이면 필터 없음(null). */
  private ReportStatus parseStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    try {
      return ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  /** 잘못된 accidentType 필터 값은 400으로 거른다(DB는 소문자 값). 빈 값이면 필터 없음(null). */
  private AccidentType parseAccidentType(String accidentType) {
    if (!StringUtils.hasText(accidentType)) {
      return null;
    }
    try {
      return AccidentType.from(accidentType);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
