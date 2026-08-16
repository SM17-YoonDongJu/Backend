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
 * {@code ai.ocr_results} 읽기 전용 뷰 — 문서별 OCR 품질 판정.
 *
 * <p><b>소유권: AI 워커.</b> Backend는 SELECT만 한다 — {@code GRANT SELECT (id, report_id, claim_id,
 * attachment_id, doc_type, doc_index, ocr_quality) ON ai.ocr_results TO app_owner}(AI 레포 PR #66,
 * 아직 배포 전). GRANT가 배포되기 전엔 이 뷰를 읽는 조회가 항상 실패하지만 {@code @Subselect}라 부팅은
 * 막히지 않는다({@link OcrJobFailureView}와 동일 근거) — GRANT가 배포되는 순간 Backend 재배포 없이
 * 자동으로 동작을 시작한다.
 *
 * <p><b>주의 — 동명이표 함정.</b> {@code core.ocr_results}라는 이름이 같은 별개 테이블이 이미 존재한다
 * (Backend가 과거에 만들었다가 AI팀이 소유권을 회수한 잔재, {@link OcrJobFailureView} javadoc 참고).
 * 앱의 기본 스키마(search_path)가 core라서 스키마를 빠뜨리면 조용히 엉뚱한 테이블을 읽는다 — 서브셀렉트에
 * {@code ai.ocr_results}로 스키마를 반드시 명시한다.
 *
 * <p>OCR은 성공했지만 신뢰도가 낮고 이름·도메인 정보가 검출되지 않은 문서가 {@code ocr_quality =
 * 'needs_reupload'}로 표시된다. 이 뷰는 그 판정과 식별 정보만 노출한다 — 원본 OCR 텍스트·엔티티 등
 * 내용 컬럼은 GRANT에서 애초에 제외돼 있어 매핑하지 않는다.
 */
@Entity
@Immutable
@Subselect("""
    select id,
           report_id,
           attachment_id,
           doc_type,
           ocr_quality
      from ai.ocr_results
    """)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OcrResultView {

  @Id
  @Column(name = "id")
  private UUID id;

  /** {@code reports.id}(Spring 생성) — 주 조인 키. */
  @Column(name = "report_id")
  private UUID reportId;

  /** {@code report_attachments.id} — 문서 단위 표시용. */
  @Column(name = "attachment_id")
  private UUID attachmentId;

  /** 문서 유형(AI측 분류, 진단서·보험증권 등). 사용자 응답엔 노출하지 않는다. */
  @Column(name = "doc_type")
  private String docType;

  /** OCR 품질 판정 문자열. {@code needs_reupload}만 사용자에게 의미가 있다. */
  @Column(name = "ocr_quality")
  private String ocrQuality;
}
