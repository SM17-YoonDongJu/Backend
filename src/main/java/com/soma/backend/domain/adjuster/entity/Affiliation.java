package com.soma.backend.domain.adjuster.entity;

import org.springframework.util.StringUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 손해사정사 소속 형태(API 명세 affiliation). 독립(개업) / 손해사정법인 소속. */
public enum Affiliation {
  INDEPENDENT,
  FIRM;

  /**
   * 요청 문자열을 소속으로 변환한다. 누락이면 {@code MISSING_REQUIRED_FIELD}, 정의되지 않은 값이면
   * {@code VALIDATION_ERROR}. 같은 성격의 변환인 {@code ChatReportReason.from}과 계약을 맞춘다.
   */
  public static Affiliation from(String raw) {
    if (!StringUtils.hasText(raw)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    try {
      return Affiliation.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
