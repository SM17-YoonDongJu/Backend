package com.soma.backend.domain.report.entity.claim;

/**
 * 특약(rider) 정보 값 객체(design.md §3.1). product_id는 무결성 보장이 불가해 제외한다(ERD 결정 #2).
 */
public record RiderInfo(String productName) {
}
