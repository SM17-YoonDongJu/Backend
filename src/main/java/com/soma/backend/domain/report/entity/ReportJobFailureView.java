package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code ai.report_job_failures} 읽기 전용 뷰 — 리포트 생성 작업 실패 저널(AI 레포 contracts.md §4-1).
 *
 * <p>리포트 워커는 실패한 리포트를 저장하지 않는다. 재전달 한도(report 운영값 3회)를 다 쓰면 초안 없이 이 저널에
 * terminal 행만 남기므로, 이 행을 읽지 않으면 그 리포트는 영원히 PROCESSING으로 보인다.
 *
 * <p><b>소유권: AI 워커.</b> Backend는 SELECT만 한다 — INSERT/UPDATE/DELETE도, Flyway로 이 테이블을
 * 생성·변경하는 것도 금지다. 원본 DDL과 {@code GRANT SELECT ... TO app_owner}는 AI 레포
 * {@code migrations/ai/012_report_job_failures.sql}(ai_owner self-grant, 워커 기동마다 재적용)이고,
 * {@code ai} 스키마 USAGE는 {@code deploy/schema_split.sql}이 준다. {@code @Table(schema="ai")}가 아니라
 * {@code @Subselect}인 이유는 {@link OcrJobFailureView}와 같다 — 012가 아직 적용되지 않은 환경에서도 부팅이
 * 막히지 않고, 장애 반경이 이 뷰를 읽는 조회로만 격리된다.
 *
 * <p><b>terminal 행과 {@code report_id}가 있는 행만 노출한다(서브셀렉트의 where).</b> 계약상
 * {@code terminal = false}는 재전달 중이라 대부분 곧 지워지고, {@code report_id IS NULL}은 본문 역직렬화
 * 실패(schema_invalid)라 조인할 키가 없다. 둘 다 무시해야 하는 행이라 호출자가 조건을 빠뜨릴 수 없게 매핑
 * 단계에서 걸러낸다. {@code report_id}는 UNIQUE라 리포트당 최대 1행이다.
 *
 * <p><b>terminal 행도 사라질 수 있다.</b> 같은 리포트의 다른 메시지가 성공하면 워커가 행을 지운다(회복).
 * 그래서 이 행에서 나온 FAILED는 저장하거나 캐시하지 않고 조회할 때마다 파생한다({@link ReportAnalysis}).
 *
 * <p><b>의도적 미매핑 컬럼</b> — {@code message_id}·{@code claim_id}·{@code job_id}·{@code attempts}는 운영
 * 추적 전용이고, {@code error_type}(예외 클래스명)은 계약상 사용자 화면 노출 금지라 애초에 읽지 않는다.
 * LLM 거절은 이 저널에 남지 않는다 — {@code reports.status='BLOCKED'}로 보인다.
 */
@Entity
@Immutable
@Subselect("""
    select id,
           report_id,
           failure_class,
           first_failed_at,
           last_failed_at
      from ai.report_job_failures
     where terminal
       and report_id is not null
    """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportJobFailureView {

  @Id
  @Column(name = "id")
  private UUID id;

  /** {@code reports.id}(Spring 생성) — 조인 키. UNIQUE라 리포트당 최대 1행이다. */
  @Column(name = "report_id")
  private UUID reportId;

  /** 외부 계약 문자열. {@link AnalysisFailureReason#fromReportJob(String)}으로만 해석한다(ACL 경계). */
  @Column(name = "failure_class")
  private String failureClass;

  @Column(name = "first_failed_at")
  private LocalDateTime firstFailedAt;

  /** 최근 실패 시각. terminal 행이면 확정 시각과 거의 같다 — 알림 스윕의 조회 창(lookback) 기준이다. */
  @Column(name = "last_failed_at")
  private LocalDateTime lastFailedAt;

  /** ACL 번역 — 외부 {@code failure_class}를 도메인 사유로 옮긴다. 미지 값도 예외 없이 UNKNOWN이다. */
  public AnalysisFailureReason reason() {
    return AnalysisFailureReason.fromReportJob(failureClass);
  }
}
