package com.soma.backend.domain.adjuster.repository;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.user.entity.QUser;
import com.soma.backend.domain.user.entity.Role;

/**
 * 손해사정사 공개 목록/검색 QueryDSL 구현. adjuster_profiles를 users와 user_id로 조인하고
 * keyword(닉네임·헤드라인 부분일치)·specialty·region을 동적 필터로, sort를 정렬로 적용한다.
 * 공개 목록·통계는 자격 인증(CERTIFICATED) 사정사만 노출·집계한다(미인증 제외).
 * text[] 컬럼(specialties·activity_region) 포함 조건은 Report 지역 필터와 동일하게 Hibernate array_contains로 표현한다.
 */
@RequiredArgsConstructor
public class AdjusterProfileRepositoryImpl implements AdjusterProfileRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Page<AdjusterCardRow> findAdjusterCards(AdjusterListCondition condition, Pageable pageable) {
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;
    QUser us = QUser.user;

    BooleanBuilder where = buildWhere(condition, ap, us);

    List<AdjusterCardRow> content = queryFactory
        .select(Projections.constructor(AdjusterCardRow.class,
            ap.userId, us.nickname, us.avatarUrl, us.role,
            ap.specialties, ap.headline, ap.ratingMean, ap.reviewCount,
            ap.career, ap.completedConsultCount, ap.activityRegion))
        .from(ap)
        .join(us).on(us.id.eq(ap.userId))
        .where(where)
        .orderBy(sortOrder(condition.sort(), ap), ap.userId.asc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    Long total = queryFactory
        .select(ap.count())
        .from(ap)
        .join(us).on(us.id.eq(ap.userId))
        .where(where)
        .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }

  @Override
  public AdjusterListMetaRow findAdjusterListMeta() {
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;
    QUser us = QUser.user;

    // 인증(CERTIFICATED) 사정사 모수 집계(검색 필터와 무관). avg/sum은 null을 무시하므로 rating_mean 보유 평균·상담 합만 잡힌다.
    Tuple agg = queryFactory
        .select(ap.count(), ap.ratingMean.avg(), ap.completedConsultCount.sum(), ap.career.avg())
        .from(ap)
        .join(us).on(us.id.eq(ap.userId))
        .where(us.role.eq(Role.CERTIFICATED_ADJUSTER))
        .fetchOne();

    if (agg == null) {
      return new AdjusterListMetaRow(0L, 0.0, 0L, 0);
    }

    // avg/sum은 Dialect에 따라 Double·BigDecimal·Long 등으로 올 수 있어 Number로 받아 변환한다(ClassCast 방지).
    Number count = agg.get(0, Number.class);
    Number avgRating = agg.get(1, Number.class);
    Number sumConsult = agg.get(2, Number.class);
    Number avgCareer = agg.get(3, Number.class);

    return new AdjusterListMetaRow(
        count == null ? 0L : count.longValue(),
        avgRating == null ? 0.0 : Math.round(avgRating.doubleValue() * 10.0) / 10.0,
        sumConsult == null ? 0L : sumConsult.longValue(),
        avgCareer == null ? 0 : (int) Math.round(avgCareer.doubleValue()));
  }

  private BooleanBuilder buildWhere(AdjusterListCondition condition, QAdjusterProfile ap, QUser us) {
    BooleanBuilder where = new BooleanBuilder();
    // 공개 목록은 자격 인증(CERTIFICATED) 사정사만 노출한다(미인증 제외).
    where.and(us.role.eq(Role.CERTIFICATED_ADJUSTER));
    if (condition.keyword() != null) {
      where.and(us.nickname.containsIgnoreCase(condition.keyword())
          .or(ap.headline.containsIgnoreCase(condition.keyword())));
    }
    if (condition.specialty() != null && !"전체".equals(condition.specialty())) {
      where.and(Expressions.booleanTemplate("array_contains({0}, {1})", ap.specialties, condition.specialty()));
    }
    BooleanBuilder region = buildRegion(condition.region(), ap);
    if (region.hasValue()) {
      where.and(region);
    }
    return where;
  }

  /** region 잠정 계약: 콤마로 이어진 지역 토큰 중 하나라도 activity_region에 포함되면 매칭(array_contains OR). */
  private BooleanBuilder buildRegion(@Nullable String region, QAdjusterProfile ap) {
    BooleanBuilder builder = new BooleanBuilder();
    if (region == null) {
      return builder;
    }
    for (String raw : region.split(",")) {
      String token = raw.trim();
      if (token.isEmpty()) {
        continue;
      }
      builder.or(Expressions.booleanTemplate("array_contains({0}, {1})", ap.activityRegion, token));
    }
    return builder;
  }

  private OrderSpecifier<?> sortOrder(@Nullable String sort, QAdjusterProfile ap) {
    String key = sort == null ? "" : sort;
    return switch (key) {
      case "career" -> ap.career.desc().nullsLast();
      case "consultCount" -> ap.completedConsultCount.desc().nullsLast();
      case "review" -> ap.reviewCount.desc().nullsLast();
      default -> ap.ratingMean.desc().nullsLast();
    };
  }
}
