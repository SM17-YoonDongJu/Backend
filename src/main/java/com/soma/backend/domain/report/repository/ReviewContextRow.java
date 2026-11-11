package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.soma.backend.domain.report.entity.AccidentType;

/**
 * 검수 화면 좌측 맥락(의뢰인·사고·상품) 프로젝션 — users + user_claims + insurance_products + insurers.
 * 청구·상품·보험사는 LEFT JOIN이라 해당 값이 없으면 null이다.
 *
 * <p>additional_information·description은 암호화(bytea) 컬럼이라 여기서 고르지 않고 컨버터가 적용되는
 * UserClaim 엔티티에서 읽는다({@code ReviewWorkspaceQueryService}).
 */
public record ReviewContextRow(
    String nickname,
    String gender,
    LocalDate birthDate,
    LocalDateTime joinedAt,
    AccidentType claimAccidentType,
    LocalDate accidentDate,
    String productName,
    String insurerName) {
}
