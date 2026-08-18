package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.OcrResultView;
import com.soma.backend.domain.report.repository.OcrResultViewRepository;

/**
 * {@code ai.ocr_results}의 품질 미달(needs_reupload) 문서 배치 조회를 별도 트랜잭션(REQUIRES_NEW)으로
 * 격리한다. 격리 이유·예외를 여기서 삼키지 않는 이유는 {@link TerminalFailureJournalReader}와 동일하다
 * (자가 호출은 프록시를 안 타고, 예외를 안에서 삼키면 Postgres aborted 세션 커밋 시도로 다시 실패한다).
 *
 * <p>GRANT(AI 레포 PR #66)가 아직 배포되지 않아 지금은 조회가 항상 실패한다. 호출자가 그 실패를 잡아 빈
 * 목록으로 degrade하므로 NEEDS_REUPLOAD 판정 자체({@code reports.status} 기반)는 영향받지 않는다 — 이
 * 조회는 "어느 문서인지"를 보강할 뿐 상태 판정에는 관여하지 않는다. GRANT가 배포되면 Backend 재배포 없이
 * 자동으로 문서가 채워지기 시작한다.
 */
@Component
@RequiredArgsConstructor
public class NeedsReuploadDocumentReader {

  private static final String NEEDS_REUPLOAD_QUALITY = "needs_reupload";

  private final OcrResultViewRepository ocrResultViewRepository;

  /** 품질 미달 문서를 리포트 id별로 묶어 반환한다. 조회 실패 시 예외를 그대로 던진다(클래스 javadoc). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, List<OcrResultView>> findNeedsReuploadDocuments(List<UUID> reportIds) {
    return ocrResultViewRepository.findAllByReportIdInAndOcrQuality(reportIds, NEEDS_REUPLOAD_QUALITY).stream()
        .filter(document -> document.getReportId() != null)
        .collect(Collectors.groupingBy(OcrResultView::getReportId));
  }
}
