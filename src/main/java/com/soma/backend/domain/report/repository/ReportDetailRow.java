package com.soma.backend.domain.report.repository;

/**
 * GET /reports/{reportId} 상세 조회 보조 네이티브 쿼리 프로젝션. Report/UserClaim/ReportIssue/
 * ReportAttachment는 각 Aggregate Repository로 조회하고, 이 프로젝션은 그 외 조인 전용 필드
 * (요청자·채택 사정사 닉네임, 보험상품/보험사명)만 담는다(design.md §6 ReportDetailResponse).
 */
public interface ReportDetailRow {

  String getClientNickname();

  String getClientGender();

  String getClientRegion();

  String getAdjusterNickname();

  String getProductName();

  String getProductCategory();

  String getInsurerName();
}
