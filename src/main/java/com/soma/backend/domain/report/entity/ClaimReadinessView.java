package com.soma.backend.domain.report.entity;

import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Subselect;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@code ai.claim_readiness} 읽기 전용 뷰 — 청구 단위 fan-in 판정과 재업로드 사유(AI 레포 contracts.md §4-2).
 *
 * <p><b>소유권: AI 워커.</b> Backend는 SELECT만 한다 — INSERT/UPDATE/DELETE도, Flyway로 이 테이블을 생성·변경하거나
 * GRANT를 넣는 것도 금지다. 원본 DDL은 AI 레포 {@code migrations/ai/010_claim_readiness.sql}(테이블)과
 * {@code 013_claim_readiness_reupload_reason.sql}({@code reupload_reason} 컬럼·CHECK·GRANT, 워커 기동마다 재적용)이다.
 *
 * <p><b>⚠️ 서브셀렉트 컬럼을 늘리지 말 것.</b> 013은 backend 롤(app_owner·app_owner_prod)에 <b>컬럼 한정</b>
 * {@code GRANT SELECT (claim_id, report_id, missing_doc_types, reupload_reason)}만 연다. 서브셀렉트가 다른 컬럼
 * ({@code status}·{@code terminal_job_ids} 등)이나 {@code *}를 고르면 바깥 쿼리가 그 컬럼을 참조하지 않아도 운영에서만
 * 42501(권한 없음)이 난다. 테스트 롤은 테이블 소유자라 보통의 통합 테스트로는 드러나지 않으므로
 * {@code ClaimReadinessViewSubselectTest}가 컬럼 목록을 문자열로 고정하고, {@code ReuploadReasonGrantIntegrationTest}가
 * 같은 컬럼 한정 GRANT를 재현해 확인한다(superuser 접속인 CI에서는 권한 검사가 없어 건너뛴다).
 *
 * <p><b>왜 {@code @Subselect}인가</b> — {@link OcrJobFailureView}와 같다. 물리 테이블로 매핑하면 부팅 검증
 * ({@code ddl-auto: validate})이 남의 팀 테이블·GRANT에 묶이지만, {@code @Subselect}는 검증·DDL 생성 대상에서 빠져
 * 010·013이 아직 없는 환경에서도 앱이 기동하고 장애 반경이 이 뷰를 읽는 조회로만 격리된다. 스키마는 반드시
 * {@code ai.}로 한정한다 — 앱 기본 스키마(core)에 이름이 같은 테이블이 생기면 조용히 엉뚱한 테이블을 읽는다
 * ({@link OcrResultView}의 동명이표 사례).
 *
 * <p><b>where로 거르지 않는다.</b> NULL 사유 행도 "행 있음"으로 세야 리포트당 행이 여럿인 계약 위반을 정확히 가릴 수
 * 있고, NULL은 {@link ReuploadCause#from}이 일반 안내로 번역한다. 계약상 리포트 1건 = 청구 1건 = 최대 1행이지만
 * {@code report_id}에 UNIQUE가 없어 판정은 호출자가 한다.
 */
@Entity
@Immutable
@Subselect("""
    select claim_id,
           report_id,
           missing_doc_types,
           reupload_reason
      from ai.claim_readiness
    """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClaimReadinessView {

  /**
   * {@code USER_CLAIMS.id}(text PK). JPA 식별자가 필요해 읽을 뿐이다 — 사용자가 어떤 청구로 막혔는지를 이어 붙일 수
   * 있는 내부 식별자라 응답·로그에 쓰지 않도록 getter를 두지 않는다.
   */
  @Id
  @Getter(AccessLevel.NONE)
  @Column(name = "claim_id")
  private String claimId;

  /** {@code reports.id}(Spring 생성) — 조인 키. */
  @Column(name = "report_id")
  private UUID reportId;

  /** 인식하지 못한 필수 유형 원값(text[]). 외부 계약 문자열이라 {@link #reuploadCause()}로만 해석한다. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "missing_doc_types", columnDefinition = "text[]")
  private List<String> missingDocTypes;

  /** 재업로드 사유 원값. NULL = 재업로드 아님(또는 013 이전 판정). {@link #reuploadCause()}로만 해석한다(ACL 경계). */
  @Column(name = "reupload_reason")
  private String reuploadReason;

  /**
   * ACL 번역 — 두 원값을 재업로드 원인으로 옮긴다. 사유별 안내가 없는 값({@code unreadable}·NULL·모르는 값)은
   * {@code null}이다(예외 없음).
   */
  public @Nullable ReuploadCause reuploadCause() {
    return ReuploadCause.from(reuploadReason, missingDocTypes);
  }
}
