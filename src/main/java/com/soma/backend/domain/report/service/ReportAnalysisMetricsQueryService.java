package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReportAnalysisMetricsResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 리포트 AI 분석 근거 수치 조회 유스케이스(GET /reports/{reportId}/analysis-metrics). 인가는 상세와 같은 규칙
 * ({@link Report#isViewableBy})이고, 수치 조회·해석·degrade는 {@link ReportAnalysisMetricsResolver}가 맡는다.
 *
 * <p><b>⚠️ 의도적으로 {@code @Transactional}이 없다 — 붙이지 말 것.</b> 조회 메서드는 readOnly 트랜잭션이라는 관례를
 * 여기에만 적용하지 않는다. {@link ReportDraftMetricsReader}가 REQUIRES_NEW라, 바깥 트랜잭션이 있으면 그 커넥션을 쥔
 * 채 커넥션을 하나 더 요구한다. 2026-11-11 dev에서 목록 조회가 바로 이 구조(바깥 C1을 쥔 채 C2·C3 요구)로 콜드
 * 10 RPS에 Hikari 풀 전체를 잡고 교착했다. 트랜잭션 없이 순차로 실행하면 ① {@code reportRepository.findById}
 * (Spring Data 기본 readOnly 트랜잭션 — 끝나면 커넥션 반납) ② 인가(DB 없음) ③ 리더(REQUIRES_NEW — 새 커넥션
 * 1개)가 서로 겹치지 않아, 요청 하나가 동시에 쥐는 커넥션은 최대 1개다. 잃는 일관성도 없다 — PostgreSQL 기본
 * READ COMMITTED에서는 바깥 트랜잭션이 있어도 문장마다 스냅샷이 다르다.
 *
 * <p>이 회귀는 {@code ReportAnalysisMetricsConnectionBudgetTest}(Hikari 풀 1개)가 잡는다 — 여기나 Resolver에
 * 트랜잭션을 붙이면 리더가 두 번째 커넥션을 기다리다 타임아웃되고, 응답이 UNAVAILABLE로 떨어져 테스트가 깨진다.
 */
@Service
@RequiredArgsConstructor
public class ReportAnalysisMetricsQueryService {

  private final ReportRepository reportRepository;
  private final ReportAnalysisMetricsResolver reportAnalysisMetricsResolver;

  /**
   * 리포트의 AI 분석 근거 수치. 존재 확인이 인가보다 먼저다(없으면 사정사가 불러도 404 — 상세와 같다).
   * {@code ai.report_drafts} 조회·해석이 실패해도 예외 없이 {@code available=false}·{@code UNAVAILABLE}로 내린다.
   *
   * @throws BusinessException 리포트가 없으면 {@code REPORT_NOT_FOUND}, 열람 권한이 없으면 {@code FORBIDDEN}
   */
  public ReportAnalysisMetricsResponse getAnalysisMetrics(UUID userId, String role, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isViewableBy(userId, role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    return reportAnalysisMetricsResolver.resolve(report);
  }
}
