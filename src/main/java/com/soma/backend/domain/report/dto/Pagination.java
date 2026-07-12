package com.soma.backend.domain.report.dto;

import org.springframework.data.domain.Page;

/** 목록 응답 공통 페이지 메타(design.md §6). page는 1-based(명세 정합). */
public record Pagination(int page, int size, long totalElements, int totalPages, boolean hasNext) {

  public static Pagination from(Page<?> page) {
    return new Pagination(
        page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
  }
}
