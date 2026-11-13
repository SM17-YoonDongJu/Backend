package com.soma.backend.domain.report.entity;

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
 * {@code ai.report_drafts}의 {@code draft -> 'metrics'} 읽기 전용 뷰 — 리포트 생성 과정의 기술 수치(AI#92, version 1).
 *
 * <p><b>소유권: AI 리포트 워커.</b> Backend는 SELECT만 한다 — INSERT/UPDATE/DELETE도, Flyway로 이 테이블을
 * 생성·변경하거나 GRANT를 넣는 것도 금지다. 원본 DDL과 {@code GRANT SELECT ... TO app_owner}(self-grant, 워커 기동마다
 * 재적용)는 AI 레포 {@code migrations/ai/007_report_drafts.sql}이 정본이다.
 *
 * <p><b>왜 {@code @Subselect}인가</b> — {@link OcrJobFailureView}와 같다. 물리 테이블로 매핑하면 부팅 검증
 * ({@code ddl-auto: validate})이 남의 팀 테이블과 GRANT에 묶이지만, {@code @Subselect}는 검증·DDL 생성 대상에서
 * 빠지므로 테이블이 없어도 앱은 기동하고 장애 반경이 이 뷰를 읽는 조회 하나로 격리된다.
 *
 * <p><b>metrics 서브트리만 읽는다.</b> {@code draft}에는 본문(sections)·쟁점 같은 큰 자유 텍스트가 함께 있는데, 이
 * 조회에 필요한 것은 수치뿐이다. 단건 PK 조회라 DB 밖(네트워크·JVM)으로는 metrics만 나간다. 스키마는 반드시
 * {@code ai.}로 한정한다 — 앱 기본 스키마(core)에 이름이 같은 테이블이 생기면 조용히 엉뚱한 테이블을 읽는다
 * ({@link OcrResultView}의 동명이표 사례).
 *
 * <p><b>{@code metrics_json}의 세 가지 의미</b> — SQL NULL은 metrics 키가 없음(AI#92 이전 리포트이거나 수치 계산
 * 실패. {@code draft}가 객체가 아니어도 {@code ->}가 NULL을 낸다), 문자열 {@code "null"}은 JSON null, 그 밖에는
 * JSON 텍스트다. 이 구분과 해석(버전 확인·허용 목록 바인딩)은 {@code ReportAnalysisMetricsResolver}의 몫이고,
 * 이 엔티티는 원문 문자열만 갖는다(Jackson·dto에 의존하지 않는다).
 *
 * <p><b>{@code status}(draft/signed/rejected)로 거르지 않는다.</b> 수치는 생성 실행의 기술 수치라 초안의 검수
 * 상태와 무관하고, Backend는 이 테이블에 쓸 권한이 없어 지금은 사실상 항상 {@code draft}다.
 */
@Entity
@Immutable
@Subselect("""
    select report_id,
           (draft -> 'metrics')::text as metrics_json
      from ai.report_drafts
    """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportDraftMetricsView {

  /** {@code reports.id}(Spring 생성, {@code ReportJob.report_id} 패스스루). PK라 리포트당 0~1행이다. */
  @Id
  @Column(name = "report_id")
  private UUID reportId;

  /** {@code draft -> 'metrics'}의 JSON 텍스트. 키가 없으면 null, JSON null이면 {@code "null"}(클래스 javadoc). */
  @Column(name = "metrics_json")
  private String metricsJson;
}
