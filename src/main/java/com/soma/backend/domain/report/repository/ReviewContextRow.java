package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 검수 화면 좌측 맥락(의뢰인·사고·상품) 네이티브 쿼리 프로젝션 — users + user_claims + insurance_products + insurers. */
public interface ReviewContextRow {

  String getNickname();

  String getGender();

  LocalDate getBirthDate();

  LocalDateTime getJoinedAt();

  String getClaimAccidentType();

  String getDiagnosis();

  LocalDate getAccidentDate();

  String getHospitalization();

  String getClaimDescription();

  String getAdditionalInformation();

  String getProductName();

  String getInsurerName();
}
