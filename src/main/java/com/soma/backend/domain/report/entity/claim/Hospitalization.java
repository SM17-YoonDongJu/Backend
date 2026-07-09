package com.soma.backend.domain.report.entity.claim;

import java.time.LocalDate;

/** 입원 기간 값 객체(design.md §3.1). ClaimDetails의 공통 구성요소로 여러 건 보유 가능. */
public record Hospitalization(LocalDate hospitalStart, LocalDate hospitalEnd, String hospitalReason) {
}
