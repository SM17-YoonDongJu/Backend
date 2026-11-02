package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportJobFailureView;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * 리포트 생성 실패 저널(ai) × reports 크로스-애그리거트 조회 QueryDSL 구현. 매핑되지 않은 연관이라 엔티티 조인
 * {@code join(rp).on(...)}으로 잇는다({@code OcrJobFailureViewRepositoryImpl}과 같은 패턴). 뷰가
 * {@code @Entity}({@code @Subselect})라 Q타입이 생성되므로 native query가 필요 없다.
 */
@RequiredArgsConstructor
public class ReportJobFailureViewRepositoryImpl implements ReportJobFailureViewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public List<PendingAnalysisFailureRow> findPendingNotification(
      LocalDateTime since, int limit, Collection<UUID> excludedReportIds) {
    QReportJobFailureView fv = QReportJobFailureView.reportJobFailureView;
    QReport rp = QReport.report;

    BooleanExpression pending = fv.lastFailedAt.goe(since)
        .and(rp.analysisFailureNotifiedAt.isNull())
        // AI 초안이 생긴 리포트는 성공이 실패를 이긴다(§8 E3). 경합으로 성공한 리포트에 terminal 행이 남을 수
        // 있어서, 이 조건이 없으면 그런 리포트가 매 사이클 배치를 채워 진짜 실패 통지를 굶긴다.
        // ListPath(text[] 매핑)에는 isNull()이 없어 템플릿으로 표현한다.
        .and(Expressions.booleanTemplate("{0} is null", rp.applicableGuarantees))
        // 종료 상태(BLOCKED·NEEDS_REUPLOAD)는 각자 전용 스윕이 통지한다 — 판정상 FAILED가 될 수 없어 여기서
        // 뽑으면 통지 시각이 끝내 NULL로 남아 배치 슬롯을 영구 점유한다.
        .and(rp.status.notIn(ReportStatus.BLOCKED, ReportStatus.NEEDS_REUPLOAD));
    if (excludedReportIds != null && !excludedReportIds.isEmpty()) {
      // limit 적용 전에 빼야 OCR 저널과 겹치는 리포트가 이 저널 몫의 슬롯을 차지하지 않는다(최대 배치 상한 100건).
      pending = pending.and(rp.id.notIn(excludedReportIds));
    }

    // terminal 조건은 뷰(서브셀렉트)가 이미 걸고, report_id가 UNIQUE라 리포트당 1행이어서 distinct도 필요 없다.
    return queryFactory
        .select(Projections.constructor(PendingAnalysisFailureRow.class, rp.id, rp.userId))
        .from(fv)
        .join(rp).on(rp.id.eq(fv.reportId))
        .where(pending)
        // 정렬 없음: 통지한 리포트는 analysis_failure_notified_at이 채워져 다음 사이클 대상에서 빠지므로 진행이 보장된다.
        .limit(limit)
        .fetch();
  }
}
