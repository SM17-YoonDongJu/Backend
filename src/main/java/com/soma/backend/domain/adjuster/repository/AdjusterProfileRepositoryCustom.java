package com.soma.backend.domain.adjuster.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 손해사정사 공개 목록/검색 동적 조회(QueryDSL). 단건 조회는 Spring Data 파생 쿼리(findByUserId)로 충분해 여기 두지 않는다. */
public interface AdjusterProfileRepositoryCustom {

  Page<AdjusterCardRow> findAdjusterCards(AdjusterListCondition condition, Pageable pageable);

  AdjusterListMetaRow findAdjusterListMeta();
}
