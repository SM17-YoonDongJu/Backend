package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 검수 화면 좌측 맥락(의뢰인·사고·상품) 네이티브 쿼리 프로젝션 — users + user_claims + insurance_products + insurers.
 *
 * <p>additional_information·description은 암호화(bytea) 전환으로 이 프로젝션에서 빠졌다 — native 결과에는
 * 컨버터가 적용되지 않으므로 UserClaim 엔티티에서 읽는다({@code ReviewWorkspaceQueryService}).
 */
public interface ReviewContextRow {

  String getNickname();

  String getGender();

  LocalDate getBirthDate();

  LocalDateTime getJoinedAt();

  String getClaimAccidentType();

  LocalDate getAccidentDate();

  String getProductName();

  String getInsurerName();
}
