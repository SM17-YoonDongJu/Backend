package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * Report Aggregate Spring Data JPA 리포지토리 + 요약/카운트 조회.
 * 동적 목록 조회는 {@link ReportRepositoryCustom}(QueryDSL)에서 구현한다.
 */
public interface ReportRepository extends JpaRepository<Report, UUID>, ReportRepositoryCustom {

  List<Report> findAllByIdIn(List<UUID> ids);

  /**
   * 상담 거절 시 "다른 COUNSELING 제안이 없으면 채택 대기로 복귀"를 원자적으로 판정하기 위한 잠금 조회.
   *
   * <p>같은 리포트의 형제 상담방을 동시에 거절하면 두 트랜잭션이 서로를 아직 COUNSELING으로 관찰해
   * 둘 다 리포트 복귀를 건너뛰고, 리포트가 COUNSELING에 영구히 갇힌다(그 뒤로 COUNSELING을 벗어나는
   * 경로가 없다 — {@code ReportNotSelectionSweeper}도 COUNSELING은 스윕하지 않는다). 이 잠금이 두 번째
   * 거절 트랜잭션을 첫 번째가 커밋할 때까지 대기시켜 형제 상태를 정확히 관찰하게 한다.
   *
   * <p>거절 경로 전용이다 — 잠금이 필요 없는 조회는 {@code findById}를 그대로 쓴다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Report r WHERE r.id = :id")
  Optional<Report> findByIdForUpdate(@Param("id") UUID id);

  /**
   * BLOCKED 알림 스윕 대상 — reports를 직접 스캔한다(ai.ocr_job_failures 저널에는 흔적이 없어 조인 불필요).
   * 파생 쿼리로 충분한 단순 조회라 QueryDSL을 쓰지 않는다(하네스 쿼리 규칙).
   */
  List<Report> findAllByStatusAndBlockedNotifiedAtIsNull(ReportStatus status, Pageable pageable);

  /**
   * NEEDS_REUPLOAD(OCR 품질 미달) 알림 스윕 대상 — BLOCKED와 같은 이유로 reports를 직접 스캔한다
   * (품질 판정이라 ai.ocr_job_failures 저널에 흔적이 없어 조인 불필요). 멱등 가드 컬럼이 달라 BLOCKED
   * 조회와 합칠 수 없다 — 파생 쿼리는 컬럼명이 메서드 이름에 박혀 파라미터화가 불가능하다.
   * 파생 쿼리로 충분한 단순 조회라 QueryDSL을 쓰지 않는다(하네스 쿼리 규칙).
   */
  List<Report> findAllByStatusAndNeedsReuploadNotifiedAtIsNull(ReportStatus status, Pageable pageable);

  /**
   * 사건번호 발급용 시퀀스의 원자 증가값. {@code nextval}은 무락·비트랜잭셔널이라 동시 호출에도 서로 막지 않고
   * 각기 다른 값을 돌려줘 사전확인·재시도가 필요 없다(롤백 시 gap 허용). 시퀀스 이름을 스키마 수식하지 않아
   * search_path로 해석된다 — 운영은 {@code core}(V47), 테스트는 {@code public}(sql/test-schema.sql)이다.
   * JPQL로 표현할 수 없어 문서화된 native 예외로 둔다 — 도메인 리포지토리에 남은 유일한 native 쿼리다.
   */
  @Query(value = "SELECT nextval('report_case_seq')", nativeQuery = true)
  long nextCaseNoSeq();

  /** 마이페이지 활동 집계 — 요청 사용자가 만든 리포트 총수(GET /users/me/activity-summary). */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.userId = :userId")
  long countByUserId(@Param("userId") UUID userId);

  /** 마이페이지 활동 집계 — 요청 사용자의 종결(CLOSED) 리포트 수. */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.userId = :userId "
      + "AND r.status = com.soma.backend.domain.report.entity.ReportStatus.CLOSED")
  long countClosedByUserId(@Param("userId") UUID userId);

  /**
   * 미채택(NOT_SELECTED) 자동 전이 대상 — 지정 상태(검수 대기·채택 대기)로 threshold 이전에 접수된 리포트.
   * 이미 CLOSED·COUNSELING·NOT_SELECTED인 리포트는 sources에 없어 자연히 제외된다.
   */
  @Query("SELECT r FROM Report r WHERE r.status IN :sources AND r.createdAt < :threshold")
  List<Report> findExpiredForNotSelection(
      @Param("sources") Collection<ReportStatus> sources, @Param("threshold") LocalDateTime threshold);
}
