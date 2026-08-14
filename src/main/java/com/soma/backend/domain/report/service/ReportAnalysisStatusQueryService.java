package com.soma.backend.domain.report.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReportAnalysisStatusResponse;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 리포트 분석(OCR·AI) 처리 상태 조회 유스케이스(design.md §4). CQRS 조회 전용이며, 판정 규칙 자체는
 * {@link ReportAnalysis}·{@code AnalysisFailureReason}(VO)이 소유한다 — 이 서비스는 조회를 조율하고
 * 결과를 옮기기만 한다(빈약한 도메인 방지).
 *
 * <p>상태는 저장하지 않고 매번 파생한다: AI 초안 존재 여부(reports) + 확정 실패 행({@code ai.ocr_job_failures}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportAnalysisStatusQueryService {

  private final ReportRepository reportRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;
  private final OcrJobFailureViewRepository ocrJobFailureViewRepository;

  /**
   * GET /reports/{reportId}/analysis-status — 폴링용 단건 조회.
   *
   * <p><b>인가: 리포트 소유자 전용</b>이다(상세 조회와 달리 사정사에게 열지 않는다 — design.md §12 S3).
   * 미존재는 404 REPORT_NOT_FOUND, 소유자가 아니면 403 FORBIDDEN.
   *
   * <p><b>왜 사정사를 뺐나</b> — 이 응답에만 있는 {@code failed_documents[].name}은 사용자가 업로드할 때
   * 준 원본 파일명({@code report_attachments.name} ← {@code CreateReportRequest.Document.name})이라
   * "환자명_진단서_병원.pdf"처럼 타인의 신원·병력을 그대로 담을 수 있다. 반면 사정사에게 이 엔드포인트가
   * 필요한 업무는 없다 — 폴링의 목적은 "업로드한 사용자가 자기 리포트가 언제 뜨는지 확인하는 것"이고,
   * 사정사가 검수 큐에서 분석 미완 리포트를 만나는 경우에 필요한 정보(처리 중인가/실패인가)는 파일명 없는
   * 평면 3필드({@code analysis_state}·{@code analysis_failure_reason}·{@code analysis_failure_message})로
   * 이미 목록·상세에 실려 나간다. 즉 사정사에게 열어봐야 추가로 주는 것은 <b>타인의 파일명뿐</b>이라
   * 최소 권한 원칙상 닫는 게 맞다. (신규 엔드포인트라 기존 소비자가 없어 축소에 따른 회귀도 없다.)
   *
   * <p>목록·상세와 달리 실패 저널 조회 오류를 <b>삼키지 않는다</b>. 이 엔드포인트의 유일한 책임이 분석 상태
   * 전달이라, 조회에 실패했는데 PROCESSING을 내리면 "영원히 처리 중"이라는 거짓을 말하게 된다(design.md §8 E14).
   */
  public ReportAnalysisStatusResponse getAnalysisStatus(UUID userId, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    // 역할(role)이 아니라 소유권으로만 판정한다 — 사정사 계정이 스스로 만든 리포트는 소유자로서 통과한다.
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    List<OcrJobFailureView> failures =
        ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(List.of(reportId));
    ReportAnalysis analysis = ReportAnalysis.of(report, failures);
    return ReportAnalysisStatusResponse.of(reportId, analysis, attachmentNames(reportId, analysis));
  }

  /**
   * 목록·상세 응답에 붙일 분석 상태를 리포트 묶음 단위로 한 번에 판정한다(쿼리 2회 — 실패 뷰 배치 + 리포트 배치).
   * 카드 쿼리에 실패 뷰를 조인하지 않는 이유는 실패 문서 N건당 카드 행이 복제되기 때문이다.
   *
   * <p><b>{@code REQUIRES_NEW}인 이유(중요):</b> {@code ai} 스키마는 다른 팀 소유라 GRANT 미적용·테이블 부재로
   * 조회가 실패할 수 있는데, 그 실패를 호출자의 트랜잭션 안에서 잡으면 Hibernate가 JDBC 예외 시 세션을
   * rollback-only로 표시해 커밋 시점에 {@code UnexpectedRollbackException}이 터진다(= degrade가 무의미해지고
   * 목록/상세가 통째로 500). 별도 트랜잭션으로 떼어내 실패 반경을 이 조회로 가두고, 호출자는 예외를 잡아
   * PROCESSING으로 degrade한다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Map<UUID, ReportAnalysis> resolveAll(Collection<UUID> reportIds) {
    if (reportIds == null || reportIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> targets = reportIds.stream().filter(Objects::nonNull).distinct().toList();
    if (targets.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<OcrJobFailureView>> failuresByReport =
        ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(targets).stream()
            .filter(failure -> failure.getReportId() != null)
            .collect(Collectors.groupingBy(OcrJobFailureView::getReportId));

    Map<UUID, ReportAnalysis> analyses = new HashMap<>();
    for (Report report : reportRepository.findAllByIdIn(targets)) {
      analyses.put(report.getId(),
          ReportAnalysis.of(report, failuresByReport.getOrDefault(report.getId(), List.of())));
    }
    return analyses;
  }

  /**
   * 실패 문서에 붙일 첨부 파일명({@code report_attachments.id → name}). 실패 문서가 없으면 조회하지 않는다.
   * 첨부 행이 없거나 이름이 비어 있으면 맵에서 빠지고 응답에는 null로 나간다.
   */
  private Map<UUID, String> attachmentNames(UUID reportId, ReportAnalysis analysis) {
    if (analysis.documents().isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new HashMap<>();
    for (ReportAttachment attachment : reportAttachmentRepository.findAllByReportId(reportId)) {
      if (attachment.getName() != null) {
        names.put(attachment.getId(), attachment.getName());
      }
    }
    return names;
  }
}
