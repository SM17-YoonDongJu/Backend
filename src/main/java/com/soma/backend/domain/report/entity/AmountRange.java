package com.soma.backend.domain.report.entity;

/**
 * 청구·제시 금액 범위 값 객체. 확정 금액을 직접 노출하지 않고 범위·headroom만 계산한다(금소법 §17).
 */
public record AmountRange(Long claimedMinAmount, Long claimedMaxAmount, Long offeredAmount) {

  /** claimed_max - offered. 음수(초과 제시) 가능. null 안전. */
  public long offerHeadroom() {
    long max = claimedMaxAmount == null ? 0L : claimedMaxAmount;
    long offered = offeredAmount == null ? 0L : offeredAmount;
    return max - offered;
  }
}
