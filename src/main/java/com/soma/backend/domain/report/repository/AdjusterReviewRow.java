package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.report.entity.AccidentType;

/**
 * 사정사 평가 목록 조회 projection. 리뷰어 닉네임 + 사건 유형(연결 리포트) + 평점·후기·작성일.
 * {@code accidentType}은 report_id가 없으면 null이며 DTO에서 표시용 문자열(item)로 변환한다.
 */
public record AdjusterReviewRow(
    String nickname,
    Integer score,
    @Nullable AccidentType accidentType,
    LocalDateTime createdAt,
    @Nullable String review) {
}
