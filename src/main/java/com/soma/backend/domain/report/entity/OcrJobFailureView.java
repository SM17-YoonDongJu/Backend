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
 * {@code ai.ocr_job_failures} 읽기 전용 뷰 — OCR 작업 실패 저널.
 *
 * <p><b>소유권: AI 워커.</b> Backend는 SELECT만 한다 — INSERT/UPDATE/DELETE도, <b>Flyway로 이 테이블을
 * 생성·변경하는 것도 금지</b>다. 원본 DDL은 AI 레포 {@code migrations/ai/008_ocr_job_failures.sql}이고,
 * {@code GRANT SELECT ... TO app_owner}는 AI 레포 {@code deploy/schema_split.sql}이 정본이다
 * (Backend 마이그레이션에 {@code GRANT}를 넣으면 소유자가 아니라 권한 오류로 배포가 통째로 깨진다).
 * 과거 {@code ocr_results}를 Backend가 만들었다가 AI팀이 회수한 소유권 충돌을 반복하지 않는다(design.md §9-2).
 *
 * <p><b>왜 {@code @Table(schema="ai")}가 아니라 {@code @Subselect}인가</b> — 물리 테이블로 매핑하면
 * {@code ddl-auto: validate}가 부팅 시 {@code ai.ocr_job_failures} 메타데이터를 검증해, GRANT가 아직
 * 적용되지 않았거나 AI팀 마이그레이션이 늦으면 <b>앱 기동 자체가 실패</b>한다(= Backend 가용성이 남의 팀
 * 배포 일정에 묶인다). {@code @Subselect}는 물리 테이블이 아니라 스키마 검증·DDL 생성 대상에서 빠지므로
 * 부팅은 항상 성공하고, 권한/테이블 부재의 장애 반경이 이 뷰를 읽는 조회로만 격리된다(design.md §3-3, §8 E14).
 * 테스트 프로파일({@code ddl-auto: create-drop})에서도 아무 것도 만들지 않는다.
 *
 * <p><b>의도적 미매핑 컬럼</b> — {@code job_id}·{@code message_id}·{@code s3_key}·{@code user_ref}·
 * {@code content_type}·{@code doc_type_hint}·{@code claim_id}·{@code attempts}는 매핑하지 않는다.
 * 내부 식별자·운영 추적 전용이며 사용자 응답에 나가면 안 되는 값이라, "애초에 읽지 않는 것"을 1차 방어로 둔다
 * (design.md §12 S2). {@code error_type}은 예외 클래스명이라 매핑하되 <b>사용자 응답에 절대 노출하지 않고</b>
 * 운영 로그·메트릭에만 쓴다.
 *
 * <p>조인 키는 {@code report_id}다 — AI 워커가 Spring이 만든 {@code reports.id}를 그대로 기록하므로
 * ({@code OcrJob.report_id} 패스스루) Backend가 {@code job_id}를 따로 영속화할 필요가 없다(design.md §0).
 */
@Entity
@Immutable
@Subselect("""
    select id,
           report_id,
           attachment_id,
           failure_class,
           error_type,
           terminal,
           first_failed_at,
           last_failed_at
      from ai.ocr_job_failures
    """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OcrJobFailureView {

  @Id
  @Column(name = "id")
  private UUID id;

  /** {@code reports.id}(Spring 생성) — 주 조인 키. 역직렬화 실패 행은 NULL이라 조인되지 않는다(§8 E8). */
  @Column(name = "report_id")
  private UUID reportId;

  /** {@code report_attachments.id} — 문서 단위 표시용. poison 훅 등에서는 NULL일 수 있다(§8 E9). */
  @Column(name = "attachment_id")
  private UUID attachmentId;

  /** 외부 계약 문자열. {@link AnalysisFailureReason#from(String)}으로만 해석한다(ACL 경계). */
  @Column(name = "failure_class")
  private String failureClass;

  /** 예외 클래스명(운영 진단 전용). <b>사용자 응답·DTO에 넣지 말 것</b>. */
  @Column(name = "error_type")
  private String errorType;

  /** 확정 실패 여부(false→true 단방향). 사용자 노출 게이트라 모든 조회가 true로 필터한다(§8 E1·E13). */
  @Column(name = "terminal")
  private boolean terminal;

  @Column(name = "first_failed_at")
  private LocalDateTime firstFailedAt;

  @Column(name = "last_failed_at")
  private LocalDateTime lastFailedAt;

  /** ACL 번역 — 외부 {@code failure_class}를 도메인 사유로 옮긴다. 미지 값은 UNKNOWN 폴백(예외 없음). */
  public AnalysisFailureReason reason() {
    return AnalysisFailureReason.from(failureClass);
  }
}
