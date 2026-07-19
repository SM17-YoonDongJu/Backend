package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;

/** Report 동적 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportRepositoryCustom {

  Page<PendingReviewRow> findPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId, Pageable pageable);

  /** 리포트 의뢰인의 지역 목록(users.region text[]). 매핑 엔티티 조인 + 배열 컬럼 단일 조회라 QueryDSL fetchOne. */
  List<String> findRegionByReportId(UUID reportId);
}
