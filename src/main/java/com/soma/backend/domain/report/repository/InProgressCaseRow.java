package com.soma.backend.domain.report.repository;

import java.util.UUID;

/** 홈 대시보드 "진행 중인 사건" 네이티브 쿼리 프로젝션. */
public interface InProgressCaseRow {

  UUID getReportId();

  String getCaseNo();

  String getAccidentType();

  String getTitle();

  String getReportStatus();

  String getReviewStatus();
}
