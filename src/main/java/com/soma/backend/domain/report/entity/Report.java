package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.crypto.converter.ReportQuestionConverter;

/**
 * REPORTS Aggregate Root. AI 초안 리포트와 사정사 검수 확정 상태를 함께 관리한다.
 * 상태 전이는 {@link #applyReviewTransition(ReportStatus)}로만 허용한다(design.md §4).
 * region 컬럼은 없다 — user_id로 users.region을 조인해서 조회한다(§1).
 */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

  private static final Map<ReportStatus, Set<ReportStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(ReportStatus.class);

  static {
    ALLOWED_TRANSITIONS.put(ReportStatus.AWAITING_INSPECTION,
        EnumSet.of(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION, ReportStatus.NOT_SELECTED));
    // 채택(제안 확정)은 상담 단계를 거치지 않고 AWAITING_ADOPTION → CLOSED로 바로 종결할 수 있다.
    ALLOWED_TRANSITIONS.put(ReportStatus.AWAITING_ADOPTION,
        EnumSet.of(ReportStatus.AWAITING_ADOPTION, ReportStatus.COUNSELING, ReportStatus.NOT_SELECTED,
            ReportStatus.CLOSED));
    ALLOWED_TRANSITIONS.put(ReportStatus.COUNSELING,
        EnumSet.of(ReportStatus.COUNSELING, ReportStatus.CLOSED, ReportStatus.AWAITING_ADOPTION));
    ALLOWED_TRANSITIONS.put(ReportStatus.CLOSED, EnumSet.of(ReportStatus.CLOSED));
    // 미채택 이후에도 상담이 잡히면 COUNSELING으로 재개 가능. 단 CLOSED 직행·재검수(AWAITING_ADOPTION 복귀)는 불가.
    ALLOWED_TRANSITIONS.put(ReportStatus.NOT_SELECTED,
        EnumSet.of(ReportStatus.NOT_SELECTED, ReportStatus.COUNSELING));
    // AI 워커가 원시 SQL로 직접 세팅(Backend 도메인 메서드를 거치지 않음). 종료 상태 — 여기서 나가는
    // 전이는 없다(applyReviewTransition을 거치는 모든 호출은 여기서 INVALID_STATE_TRANSITION으로 막힌다).
    ALLOWED_TRANSITIONS.put(ReportStatus.BLOCKED, EnumSet.of(ReportStatus.BLOCKED));
  }

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id")
  private UUID adjusterId;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "claim_id")
  private UUID claimId;

  @Column(name = "case_no", nullable = false, length = 100)
  private String caseNo;

  @Column(name = "title")
  private String title;

  @Convert(converter = AccidentTypeConverter.class)
  @Column(name = "accident_type", nullable = false, length = 30)
  private AccidentType accidentType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReportStatus status;

  @Column(name = "claimed_min_amount")
  private Integer claimedMinAmount;

  @Column(name = "claimed_max_amount")
  private Integer claimedMaxAmount;

  @Column(name = "offered_amount")
  private Integer offeredAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "applicable_guarantees", columnDefinition = "text[]")
  private List<String> applicableGuarantees;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "omitted_special_contract", columnDefinition = "text[]")
  private List<String> omittedSpecialContract;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "basis_terms_precedents", columnDefinition = "text[]")
  private List<String> basisTermsPrecedents;

  @Column(name = "treatment")
  private String treatment;

  @Convert(converter = ReportQuestionConverter.class)
  @Column(name = "question")
  private String question;

  @Column(name = "confidence_level", length = 10)
  private String confidenceLevel;

  @Column(name = "is_masked")
  private Boolean isMasked;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "documents", columnDefinition = "jsonb")
  private Map<String, String> documents;

  /**
   * 분석 실패 알림을 보낸 시각(멱등 가드). NULL이면 미통지다. 실패 <b>상태</b>는 저장하지 않는다 —
   * {@code ai.ocr_job_failures} 파생값이라 저장하면 정상 회복 전이를 놓친다(design.md §8 E2).
   */
  @Column(name = "analysis_failure_notified_at")
  private LocalDateTime analysisFailureNotifiedAt;

  /**
   * BLOCKED(AI 입력 가드레일 차단) 알림을 보낸 시각(멱등 가드). NULL이면 미통지다. {@code status}는
   * AI 워커가 원시 SQL로 직접 세팅하므로(도메인 메서드를 거치지 않음) 이 컬럼만 Backend가 관리한다.
   */
  @Column(name = "blocked_notified_at")
  private LocalDateTime blockedNotifiedAt;

  /**
   * 리포트 생성 진입점(design.md §3). OCR·AI 분석 전 상태이므로 status=AWAITING_INSPECTION으로 시작한다.
   */
  public static Report createPending(UUID userId, UUID productId, UUID claimId, AccidentType accidentType,
      String question, String caseNo) {
    Report report = new Report();
    report.userId = userId;
    report.productId = productId;
    report.claimId = claimId;
    report.accidentType = accidentType;
    report.question = question;
    report.caseNo = caseNo;
    report.status = ReportStatus.AWAITING_INSPECTION;
    return report;
  }

  public AmountRange amountRange() {
    return new AmountRange(claimedMinAmount, claimedMaxAmount, offeredAmount);
  }

  /**
   * 사용자가 제안(REPORT_REVIEWS)을 채택해 담당 사정사를 확정하고 리포트를 종결한다(design.md §6 decide).
   * 채택 대기(AWAITING_ADOPTION)·상담 중(COUNSELING)에서 CLOSED로 전이한다 — 상담을 거치지 않고 바로
   * 채택할 수 있다. 이미 CLOSED면 409 REPORT_ALREADY_CLOSED, 그 외(검수 전·미채택)면 409
   * INVALID_STATE_TRANSITION. 둘 다 상태 충돌이라 클라이언트가 code로 구분한다.
   */
  public void accept(UUID adjusterId) {
    if (this.status == ReportStatus.CLOSED) {
      throw new BusinessException(ErrorCode.REPORT_ALREADY_CLOSED);
    }
    if (this.status != ReportStatus.AWAITING_ADOPTION && this.status != ReportStatus.COUNSELING) {
      throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }
    this.adjusterId = adjusterId;
    applyReviewTransition(ReportStatus.CLOSED);
  }

  /**
   * 상담 거절 시 리포트를 재채택 대기(AWAITING_ADOPTION)로 되돌린다(채팅 거절 플로우, chat 도메인 호출).
   * COUNSELING 상태에서만 허용 — 그 외 상태면 INVALID_STATE_TRANSITION.
   */
  public void reopenForAdoption() {
    if (this.status != ReportStatus.COUNSELING) {
      throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }
    applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
  }

  /**
   * 미채택(NOT_SELECTED) 자동 전이. 접수 후 기한(1주일) 내에 상담 완료(CLOSED)되지 못했거나(케이스1)
   * 검수를 하나도 받지 못한(케이스2) 리포트를 스케줄러 스윕이 전이시킨다. 검수 대기·채택 대기에서만
   * 진입한다(전이표) — 이후 {@link #applyReviewStart()}가 NOT_SELECTED에서 예외를 던져 신규 사정사 검수는
   * 차단되고, 진행 중인 상담/채팅은 이 전이로 닫지 않는다(chat 도메인이 별도 소유). 종료 상태는 아니며,
   * 이후 상담이 잡히면 COUNSELING으로 재개될 수 있다(전이표: NOT_SELECTED → COUNSELING 허용).
   */
  public void markNotSelected() {
    applyReviewTransition(ReportStatus.NOT_SELECTED);
  }

  /**
   * AI 초안(리포트 본문)이 생성됐는지 — 분석 처리 상태 판정의 "성공" 신호다({@link ReportAnalysis} 우선순위 1번).
   *
   * <p>근거: AI 워커의 persist 노드가 {@code applicable_guarantees}·{@code omitted_special_contract}·
   * {@code basis_terms_precedents}·{@code claimed_min/max_amount}·{@code status}를 한 UPDATE로 함께 쓰므로
   * (AI 레포 {@code report_worker/nodes/agents.py:931-941}) 대표값 하나로 판별할 수 있다.
   *
   * <p><b>2026-08-14 갱신 — claim_id 경로는 이제 동작한다.</b> AI 레포 PR #58(패스스루 + fan-in 게이팅)이
   * 머지되어, {@code claim_id}가 있는 정상 흐름은 {@code job.report_id}를 그대로 써서 UPDATE가 행을 찾는다
   * (report_worker가 {@code applicable_guarantees}를 정상적으로 채운다). {@code claim_id}가 없는 레거시/단독
   * 경로만 여전히 {@code ocr_result_id}에서 파생한 UUID를 써서 0행 갱신 문제가 남아 있다. 이 메서드는 코드
   * 변경 없이 그대로 동작한다(design.md §0-1, §4-2) — 판정 자체는 여전히 {@code applicable_guarantees} 존재
   * 여부 하나로 충분하다.
   */
  public boolean isAiDraftGenerated() {
    return applicableGuarantees != null;
  }

  /**
   * 분석 실패 알림 발송을 1회만 기록한다(단방향 가드: null → 시각). 이미 통지된 리포트는 다시 통지하지 않는다.
   * 재업로드는 새 리포트를 만들므로(POST /reports) 리포트당 1회여도 새 실패는 자연히 새로 통지된다.
   *
   * @return 이번 호출이 통지 시각을 기록했으면 {@code true}, 이미 통지돼 건너뛰었으면 {@code false}
   */
  public boolean markAnalysisFailureNotified() {
    if (this.analysisFailureNotifiedAt != null) {
      return false;
    }
    this.analysisFailureNotifiedAt = LocalDateTime.now();
    return true;
  }

  /**
   * BLOCKED 알림 발송을 1회만 기록한다(단방향 가드: null → 시각). {@code status}는 AI 워커가 직접 세팅하는
   * 종료 상태라(§{@link ReportStatus#BLOCKED}) 재차단·재통지 시나리오가 없다 — 재시도는 항상 새 리포트다.
   *
   * @return 이번 호출이 통지 시각을 기록했으면 {@code true}, 이미 통지돼 건너뛰었으면 {@code false}
   */
  public boolean markBlockedNotified() {
    if (this.blockedNotifiedAt != null) {
      return false;
    }
    this.blockedNotifiedAt = LocalDateTime.now();
    return true;
  }

  /** 리포트 소유자(요청 사용자) 여부 — 상세/제안/decide 인가 가드에 사용(design.md §8). */
  public boolean isOwnedBy(UUID userId) {
    return this.userId != null && this.userId.equals(userId);
  }

  /**
   * 사정사 검수 화면(상세 조회·검수 워크스페이스) 노출 대상 상태인지. 검수 대기(AWAITING_INSPECTION)·
   * 채택 대기(AWAITING_ADOPTION)에서만 검수 화면을 연다 — 그 외(COUNSELING·CLOSED·NOT_SELECTED)는 노출하지 않는다.
   */
  public boolean isInReviewPhase() {
    return this.status == ReportStatus.AWAITING_INSPECTION || this.status == ReportStatus.AWAITING_ADOPTION;
  }

  /**
   * 검수 반영에 의한 상태 전이. 허용표(design.md §4)를 벗어나면 409 INVALID_STATE_TRANSITION.
   */
  public void applyReviewTransition(ReportStatus target) {
    Set<ReportStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(status, Set.of());
    if (!allowed.contains(target)) {
      throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }
    this.status = target;
  }

  /**
   * 사정사 검수 착수/반영에 의한 상태 파생 전이. 사정사는 target을 지정하지 않고 현재 status에서 파생한다.
   * AWAITING_INSPECTION → AWAITING_ADOPTION(착수), AWAITING_ADOPTION → 유지(재반영). 그 외(COUNSELING·CLOSED)는
   * 검수 대상이 아니므로 409 INVALID_STATE_TRANSITION. 검수 내용은 REPORTS가 아니라 REPORT_REVIEWS에만 저장하며,
   * REPORTS는 이 생명주기 status 전이만 반영한다(A8 격리).
   */
  public void applyReviewStart() {
    ReportStatus target = switch (status) {
      case AWAITING_INSPECTION, AWAITING_ADOPTION -> ReportStatus.AWAITING_ADOPTION;
      default -> throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    };
    applyReviewTransition(target);
  }
}
