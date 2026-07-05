package com.soma.backend.global.response;

import java.util.List;

import org.springframework.data.domain.Page;

/** 페이지 목록 응답 공통 DTO(0-base page). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

  public static <T> PageResponse<T> of(Page<T> page) {
    return new PageResponse<>(
        page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements, int totalPages) {
    return new PageResponse<>(items, page, size, totalElements, totalPages);
  }
}
