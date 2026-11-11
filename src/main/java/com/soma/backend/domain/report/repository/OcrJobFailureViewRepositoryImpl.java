package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.QOcrJobFailureView;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * 실패 저널(ai) × reports 크로스-애그리거트 조회 QueryDSL 구현. 매핑되지 않은 연관이라 엔티티 조인
 * {@code join(rp).on(...)}으로 잇는다(기존 {@code ReportRepositoryImpl} 패턴과 동일).
 *
 * <p>뷰가 {@code @Entity}({@code @Subselect})로 매핑돼 있어 Q타입이 생성되므로 native query가 필요 없다 —
 * 하네스의 "조회 native 금지" 규칙에 대한 예외 대상이 아니다.
 */
@RequiredArgsConstructor
public class OcrJobFailureViewRepositoryImpl implements OcrJobFailureViewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<PendingAnalysisFailureRow> findPendingNotification(LocalDateTime since, int limit) {
    QOcrJobFailureView fv = QOcrJobFailureView.ocrJobFailureView;
    QReport rp = QReport.report;

    return queryFactory
        .select(Projections.constructor(PendingAnalysisFailureRow.class, rp.id, rp.userId))
        .distinct()
        .from(fv)
        .join(rp).on(rp.id.eq(fv.reportId))
        .where(fv.terminal.isTrue()
            .and(fv.lastFailedAt.goe(since))
            .and(rp.analysisFailureNotifiedAt.isNull())
            // AI 초안이 생긴 리포트는 성공이 실패를 이기므로 통지 대상이 아니다(§8 E3).
            // Report.isAiDraftGenerated()와 같은 신호(applicable_guarantees)를 쓴다 — 이 조건이 없으면
            // 회복된(실패 행이 남은) 리포트가 매 사이클 배치를 채워 진짜 실패 통지를 굶긴다.
            // ListPath(text[] 매핑)에는 isNull()이 없어 템플릿으로 표현한다.
            .and(Expressions.booleanTemplate("{0} is null", rp.applicableGuarantees))
            // 종료 상태(BLOCKED·NEEDS_REUPLOAD)는 각자 전용 스윕이 통지하므로 여기서는 제외한다.
            // ReportAnalysis 판정이 이 두 상태를 FAILED보다 먼저 잡아 isFailed()=false가 되므로 호출부는
            // 통지를 건너뛰는데, analysis_failure_notified_at은 끝내 NULL이라 매 사이클 같은 행이 다시
            // 뽑혀 배치 슬롯을 영구 점유한다 — 그러면 진짜 실패 통지가 굶는다.
            .and(rp.status.notIn(ReportStatus.BLOCKED, ReportStatus.NEEDS_REUPLOAD)))
        // 정렬 없음: 통지한 리포트는 analysis_failure_notified_at이 채워져 다음 사이클의 대상에서 빠지므로
        // 배치가 고정되지 않고 진행이 보장된다. distinct + 미선택 컬럼 정렬은 PostgreSQL에서 금지되기도 한다.
        .limit(limit)
        .fetch();
  }
}
