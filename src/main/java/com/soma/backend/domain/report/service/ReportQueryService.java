package com.soma.backend.domain.report.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 조회 유스케이스(design.md §6) — 목록·상세. CQRS 조회 전용. 소유자(userId=principal) 스코프로
 * 본인 리포트를 전 상태(AWAITING_INSPECTION 포함) 반환한다. 상세 조회는 소유자(USER) 또는 사정사만 허용한다.
 *
 * <p><b>⚠️ 의도적으로 {@code @Transactional}이 없다 — 붙이지 말 것(#406).</b> 조회 메서드는 readOnly 트랜잭션이라는
 * 관례를 여기에는 적용하지 않는다. 분석 상태를 읽는 리더(실패 저널·품질 미달 문서·재업로드 사유)가 REQUIRES_NEW라, 바깥
 * 트랜잭션이 있으면 그 커넥션을 쥔 채 커넥션을 하나 더 요구한다. #406 전에는 이 클래스의 readOnly 트랜잭션 +
 * {@code resolveAll}의 REQUIRES_NEW + 리더로 요청 하나가 커넥션을 최대 3개 쥐었고, Hikari 풀 15개에서 동시 요청이 8건이면
 * 서로 다음 커넥션을 기다리며 교착했다(2026-11-11 콜드 10 RPS, 11-16 약 130 RPS dev 부하테스트).
 *
 * <p>지금은 ① 카드 쿼리(레코드 프로젝션이라 지연 로딩이 없어 트랜잭션 없이 불러도 되고, 트랜잭션이 없으니 조회마다
 * 커넥션을 얻고 바로 반납한다) 또는 상세 본 조회({@link CustomerReportDetailReader}의 짧은 readOnly 트랜잭션 하나)
 * ② {@code resolveAll}(트랜잭션 없음) ③ 리더
 * (REQUIRES_NEW, 하나씩 차례로)가 서로 겹치지 않아, 요청 하나가 동시에 쥐는 커넥션은 1개다(운영 KMS의 콜드 스타트 예외는
 * {@link ReportAnalysisResolver#resolveAll} 참고). 잃는 일관성도 없다 — PostgreSQL 기본 READ COMMITTED에서는 바깥
 * 트랜잭션이 있어도 문장마다 스냅샷이 다르다.
 *
 * <p>이 회귀는 {@code ReportQueryConnectionBudgetTest}(Hikari 풀 1개)가 잡는다. 여기나 {@code resolveAll}에 트랜잭션을
 * 붙이면 두 번째 커넥션을 기다리다 타임아웃돼, 응답이 500이 되거나 판정이 PROCESSING으로 낮아져 테스트가 깨진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportQueryService {

  /** 페이지 크기 상한 — page size<=0(PageRequest.of가 IllegalArgumentException)·과대 page size(성능/메모리) 방어. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reportRepository;
  private final CustomerReportDetailReader customerReportDetailReader;
  private final ReportAnalysisResolver reportAnalysisResolver;

  /** userId 소유 리포트 카드 목록. status는 옵션 필터(REPORTS.status), page는 1-based. */
  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {
    ReportStatus statusFilter = parseStatus(status);
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, statusFilter, pageable);
    return ReportCardListResponse.from(rows, analyses(rows));
  }

  /**
   * 받은 제안 목록(GET /me/received-proposals) — 제안 받은(REJECTED 제외) 리뷰를 per-review 카드로 반환한다.ㅌ
   */
  public ReportCardListResponse getReceivedProposals(UUID userId, int page, int size) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findReportsWithProposals(userId, pageable);
    return ReportCardListResponse.from(rows, analyses(rows));
  }

  /**
   * 고객 리포트 상세(GET /reports/{reportId}). 인가는 리포트 소유자(USER, report.userId=principal) 또는
   * 사정사(CERTIFICATED/UNCERTIFICATED_ADJUSTER)만 허용, 예외는 403 FORBIDDEN
   * 존재하지 않는 리포트는 404 REPORT_NOT_FOUND. 확정 배열·검수 코멘트·담당 사정사는 크로스-애그리거트
   * 읽기 모델로, 쟁점은 report_issues에서 조립
   */
  public CustomerReportDetailResponse getReportDetail(UUID userId, String role, UUID reportId) {
    // 존재 확인(404)·인가(403)·쟁점·상세 행·채택 검수 의견은 Reader의 readOnly 트랜잭션 하나에서 끝난다.
    CustomerReportDetailReader.Snapshot detail = customerReportDetailReader.read(userId, role, reportId);
    // 본 조회 트랜잭션이 끝나 커넥션을 반납한 뒤에 분석 상태를 판정한다 — 겹치면 요청 하나가 커넥션을 2개 이상 쥔다.
    ReportAnalysis analysis = analysesOrDegrade(List.of(reportId)).get(reportId);
    return CustomerReportDetailResponse.from(detail.report(), detail.issues(), detail.row(),
        detail.adjusterOpinionByIssueId(), analysis);
  }

  /** 카드 페이지에 붙일 분석 상태를 리포트 id 배치 조회 1회로 만든다(카드 쿼리 조인 금지 — 행 복제). */
  private Map<UUID, ReportAnalysis> analyses(Page<ReportCardRow> rows) {
    return analysesOrDegrade(rows.getContent().stream().map(ReportCardRow::reportId).toList());
  }

  /**
   * 분석 상태 배치 조회 + degrade. 실패 저널({@code ai.ocr_job_failures}·{@code ai.report_job_failures})은 AI
   * 워커 소유라 GRANT 미적용·미배포 상태가 있을 수 있는데, 목록·상세는 서비스의 핵심 화면이라 그 이유로 통째로
   * 500이 되면 안 된다.
   *
   * <p>저널(확정 실패) 조회 실패는 {@code resolveAll} 안에서 이미 리포트 단위로 흡수돼, {@code BLOCKED}·
   * {@code NEEDS_REUPLOAD}처럼 {@code reports.status}만으로 판정되는 상태는 살아남는다. 여기서 잡는
   * {@code DataAccessException}은 {@code resolveAll} 자체가 실패하는(리포트 배치 조회 실패 등) 더 드문
   * 경우를 위한 마지막 방어선이라, 이때는 분석 필드 전부를 PROCESSING으로 낮춰 내린다(가용성 우선).
   *
   * <p>바깥 트랜잭션이 없으므로 여기서 예외를 잡아도 rollback-only로 오염될 트랜잭션 자체가 없다. 리포트 재조회가 커넥션을
   * 못 얻어 실패해도 {@code DataAccessException}({@code DataAccessResourceFailureException})으로 올라와 여기서 흡수된다.
   * #406 전에는 {@code resolveAll} 자신의 REQUIRES_NEW 시작 실패가 {@code CannotCreateTransactionException}
   * ({@code DataAccessException}이 아니다)이라 흡수되지 않고 500으로 샜다. 로그에는 내부 식별자(s3_key·user_ref)를 남기지
   * 않는다(design.md §12 S4).
   */
  private Map<UUID, ReportAnalysis> analysesOrDegrade(Collection<UUID> reportIds) {
    try {
      return reportAnalysisResolver.resolveAll(reportIds);
    } catch (DataAccessException ex) {
      log.warn("분석 처리 상태 조회 실패 — PROCESSING으로 degrade한다(대상 {}건). cause={}",
          reportIds.size(), ex.getMostSpecificCause().getMessage());
      return Map.of();
    }
  }

  /** size 하한 1(PageRequest.of 예외 방지)·상한 MAX_PAGE_SIZE로 클램프. page 클램프와 동일한 방어 톤. */
  private int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  //도메인 엔티티로
  /** status 파라미터를 enum으로 파싱한다. 빈 값이면 필터 없음(null), 알 수 없는 값이면 400 VALIDATION_ERROR. */
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
}
