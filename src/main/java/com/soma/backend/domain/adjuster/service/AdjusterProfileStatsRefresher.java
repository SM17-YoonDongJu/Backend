package com.soma.backend.domain.adjuster.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.chat.entity.event.ConsultationAcceptedEvent;
import com.soma.backend.domain.report.entity.event.AdjusterReviewCreatedEvent;
import com.soma.backend.domain.report.repository.AdjusterRatingAggregate;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * AdjusterProfile 비정규화 집계(rating_mean·review_count·completed_consult_count) 갱신기.
 *
 * <p>이 Aggregate의 집계 컬럼을 바꾸는 <b>유일한 진입점</b>이다. report(평가 등록)·chat(상담 수락) 컨텍스트는
 * 이 클래스를 직접 부르지 않고 도메인 이벤트({@link AdjusterReviewCreatedEvent}·{@link ConsultationAcceptedEvent})만
 * 발행하며, 여기서 그 이벤트를 받아 갱신한다 — 발행하는 쪽은 adjuster 내부를 모른다. 값은 증분이 아니라 원천 테이블 전량
 * 재집계로 덮어쓰므로 멱등이고(같은 입력 → 같은 값), 한 번 어긋나도 다음 이벤트에서 스스로 복구된다.
 * 백필 마이그레이션(V46)과 계산식이 같아 "백필 값 == 런타임 값"이 검증 가능한 명제가 된다.
 *
 * <p>리스너는 {@code @EventListener}(동기)다. {@code publishEvent} 호출 지점에서 바로 실행되고 기본 전파(REQUIRED)라
 * 발행자(평가 등록·상담 수락)의 트랜잭션에 합류한다 — 사용자는 응답 직후 갱신된 값을 읽을 수 있어야 하므로
 * {@code @TransactionalEventListener(AFTER_COMMIT)}나 비동기로 미루지 않는다. 예외도 발행자에게 그대로 전파된다.
 *
 * <p>프로필 행이 없으면(자격 신청 승인 플로우가 아직 없어 실제로 가능하다) 경고 로그만 남기고 조용히
 * 건너뛴다 — 운영 데이터 공백 때문에 사용자의 평가 등록·상담 수락 자체가 실패해선 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdjusterProfileStatsRefresher {

  private final AdjusterProfileRepository adjusterProfileRepository;
  private final AdjusterReviewRepository adjusterReviewRepository;
  private final ReportReviewRepository reportReviewRepository;

  /** 평가 등록 이벤트 → 대상 사정사의 평점 재집계. */
  @EventListener
  @Transactional
  public void onAdjusterReviewCreated(AdjusterReviewCreatedEvent event) {
    refreshRating(event.adjusterId());
  }

  /** 상담 수락 이벤트 → 담당 사정사의 상담 완료 수 재집계(내부에서 adjuster_profiles 행을 잠근다). */
  @EventListener
  @Transactional
  public void onConsultationAccepted(ConsultationAcceptedEvent event) {
    refreshCompletedConsultCount(event.adjusterId());
  }

  /** 평점(rating_mean·review_count)을 adjuster_reviews 전량 재집계로 갱신한다. */
  @Transactional
  public void refreshRating(UUID adjusterUserId) {
    Optional<AdjusterProfile> found = lockProfile(adjusterUserId, "rating");
    if (found.isEmpty()) {
      return;
    }
    AdjusterRatingAggregate aggregate = adjusterReviewRepository.aggregateRating(adjusterUserId);
    found.get().refreshRating(aggregate.averageAsBigDecimal(), Math.toIntExact(aggregate.count()));
  }

  /** 상담 완료 수(completed_consult_count)를 report_reviews ACCEPTED 전량 재집계로 갱신한다. */
  @Transactional
  public void refreshCompletedConsultCount(UUID adjusterUserId) {
    Optional<AdjusterProfile> found = lockProfile(adjusterUserId, "completedConsultCount");
    if (found.isEmpty()) {
      return;
    }
    long accepted = reportReviewRepository.countAcceptedByAdjusterId(adjusterUserId);
    found.get().refreshCompletedConsultCount(Math.toIntExact(accepted));
  }

  /**
   * 갱신 대상 프로필 행을 잠근다. 집계 쿼리보다 먼저 잡아야 동시 갱신이 옛 모수로 계산하는 것을 막는다.
   * 행이 없으면 비어 있는 Optional을 돌려주고 호출자는 no-op으로 끝낸다(예외 금지).
   */
  private Optional<AdjusterProfile> lockProfile(UUID adjusterUserId, String stat) {
    Optional<AdjusterProfile> found = adjusterProfileRepository.findByUserIdForUpdate(adjusterUserId);
    if (found.isEmpty()) {
      log.warn("adjuster_profiles 행이 없어 {} 집계 갱신을 건너뜁니다. adjusterUserId={}", stat, adjusterUserId);
    }
    return found;
  }
}
