package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ProposalRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 사정사 제안 목록 조회(design.md §6) — 본인 리포트만 접근 가능. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalQueryService {

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;

  public ProposalListResponse getProposals(UUID userId, UUID reportId, int page, int size) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
    Page<ProposalRow> rows = reportReviewRepository.findProposalRows(reportId, pageable);
    return ProposalListResponse.from(rows);
  }
}
