package com.soma.backend.global.common;

import org.springframework.data.domain.PageRequest;

/**
 * 페이지 요청 변환 유틸. 외부 API는 1-based page(1부터), 내부 Spring Data는 0-based로 통일한다.
 * size는 1~{@value #MAX_PAGE_SIZE}로 클램프해 {@code PageRequest.of}의 IllegalArgumentException(size&lt;1 →
 * 500)과 과대 size(성능·메모리)를 함께 방어한다.
 */
public final class PageRequests {

  /** 페이지 크기 상한. */
  public static final int MAX_PAGE_SIZE = 100;

  private PageRequests() {
  }

  /**
   * 1-based page와 size를 0-based {@link PageRequest}로 변환한다. page&lt;1은 첫 페이지(0)로,
   * size는 1~{@link #MAX_PAGE_SIZE}로 클램프한다.
   */
  public static PageRequest ofOneBased(int page, int size) {
    int zeroBasedPage = Math.max(page - 1, 0);
    int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return PageRequest.of(zeroBasedPage, clampedSize);
  }
}
