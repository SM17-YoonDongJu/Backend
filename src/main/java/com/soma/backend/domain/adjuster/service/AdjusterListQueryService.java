package com.soma.backend.domain.adjuster.service;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterListResponse;
import com.soma.backend.domain.adjuster.repository.AdjusterCardRow;
import com.soma.backend.domain.adjuster.repository.AdjusterListCondition;
import com.soma.backend.domain.adjuster.repository.AdjusterListMetaRow;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * 손해사정사 공개 목록/검색 유스케이스(GET /adjusters). 고객 앱 파인더·홈 추천이 공유한다.
 *
 * <p>읽기 전용 크로스-애그리거트 뷰로 AdjusterProfile(adjuster)과 User(user)를 user_id로만 조립한다.
 * page는 1-based 계약이라 0-based Pageable로 변환하고, size는 [1, MAX_SIZE]로 클램프한다. 목록·통계는 인증
 * (CERTIFICATED) 사정사만 대상으로 하며, 통계 밴드(meta)는 검색 필터와 무관한 모수 집계라 목록 조회와 별개로 한 번 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdjusterListQueryService {

  private static final int DEFAULT_SIZE = 12;
  private static final int MAX_SIZE = 50;

  private final AdjusterProfileRepository adjusterProfileRepository;
  private final S3UploadService s3UploadService;

  public AdjusterListResponse getAdjusters(
      String keyword, String specialty, String region, String sort, int page, int size) {
    int pageNumber = page < 1 ? 1 : page;
    int pageSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    Pageable pageable = PageRequest.of(pageNumber - 1, pageSize);

    AdjusterListCondition condition = new AdjusterListCondition(
        trimToNull(keyword), trimToNull(specialty), trimToNull(region), trimToNull(sort));

    Page<AdjusterCardRow> cards = adjusterProfileRepository.findAdjusterCards(condition, pageable);
    AdjusterListMetaRow meta = adjusterProfileRepository.findAdjusterListMeta();
    return AdjusterListResponse.from(cards, pageNumber, meta, s3UploadService::presignedGetUrl);
  }

  @Nullable
  private static String trimToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
