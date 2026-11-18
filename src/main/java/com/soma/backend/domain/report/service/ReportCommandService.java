package com.soma.backend.domain.report.service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.service.ConsultationRoomOpener;
import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.event.ConsultationRequestedEvent;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.sqs.OcrJob;
import com.soma.backend.infra.sqs.OcrJobOutboxPort;

/**
 * 리포트 생성·제안 결정 커맨드 유스케이스(design.md §6). createReport는 UserClaim·Report(shell)·첨부를
 * 한 트랜잭션에 저장하고, 문서 1건당 OCR 트리거를 아웃박스로 발행(같은 트랜잭션에서 원자적 적재)한다.
 * OcrJob에 claim/report/attachment 참조 키를 실어, FastAPI가 OCR·AI 결과로 해당 행을 UPDATE하게 한다.
 */
@Service
@Transactional
public class ReportCommandService {

  private final UserClaimRepository userClaimRepository;
  private final ReportRepository reportRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final OcrJobOutboxPort ocrJobOutboxPort;
  private final ConsultationRoomOpener consultationRoomOpener;
  private final ApplicationEventPublisher eventPublisher;
  private final MeterRegistry meterRegistry;

  // 쓰기 경로 구간 계측(#306). http.server.requests는 엔드포인트 총 시간만 주므로 영속화 호출 구간을 따로 잰다.
  // 사건번호 발급 구간(report.create.caseno)과 발급~커밋 임계 구간(report.create.lock_hold) 타이머는 사건번호를
  // 저장·발급하지 않게 되면서 잴 구간이 없어져 함께 없앴다(#413).
  private final Timer persistTimer;

  /**
   * 리포트 생성·제안 결정에 필요한 리포지토리·아웃박스·협력 객체를 받고, 영속화 구간 타이머를 등록한다.
   *
   * @param meterRegistry {@code report.create.persist} 타이머를 등록할 레지스트리
   */
  public ReportCommandService(
      UserClaimRepository userClaimRepository,
      ReportRepository reportRepository,
      ReportAttachmentRepository reportAttachmentRepository,
      ReportReviewRepository reportReviewRepository,
      OcrJobOutboxPort ocrJobOutboxPort,
      ConsultationRoomOpener consultationRoomOpener,
      ApplicationEventPublisher eventPublisher,
      MeterRegistry meterRegistry) {
    this.userClaimRepository = userClaimRepository;
    this.reportRepository = reportRepository;
    this.reportAttachmentRepository = reportAttachmentRepository;
    this.reportReviewRepository = reportReviewRepository;
    this.ocrJobOutboxPort = ocrJobOutboxPort;
    this.consultationRoomOpener = consultationRoomOpener;
    this.eventPublisher = eventPublisher;
    this.meterRegistry = meterRegistry;
    this.persistTimer = Timer.builder("report.create.persist")
        .description("리포트 생성의 영속화 호출 구간(claim·report·첨부·아웃박스). 커밋 flush는 제외")
        .register(meterRegistry);
  }

  /**
   * POST /reports — 사고 정보 입력 수신 → 저장 → OCR 트리거 발행. 202(비동기).
   *
   * <p>접수 제시액({@code offered_amount})은 UserClaim과 Report 양쪽에 같은 값으로 넣는다. AI 워커는
   * {@code reports.offered_amount}를 읽어 청구 가능 범위를 계산하므로 리포트 쪽이 비면 범위가 0~0이 된다(#408).
   */
  public CreateReportResponse createReport(UUID userId, CreateReportRequest request) {

    if (request.accidentType() == null || request.accidentDate() == null) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }

    // 문서 수 상한은 DB 쓰기 전에 검증한다 — fail-fast
    List<CreateReportRequest.Document> documents =
        request.documents() == null ? List.of() : request.documents();

    ReportAttachment.validateCount(documents.size());

    int docTotal = documents.size();

    //accidentType에 따른 claimDetails 생성
    ClaimDetails details =
        ClaimDetails.of(request.accidentType(), request.diagnosis(), request.hospitalizations());

    //DB 저장
    Timer.Sample persistSample = Timer.start(meterRegistry);

    UserClaim claim = userClaimRepository.save(UserClaim.create(
        userId, request.productId(), request.offeredAmount(), request.accidentDate(),
        request.accidentType(), details, request.question(), request.description(),
        request.additionalInformation()));

    // Reports 테이블 스켈레톤 저장. 사건번호는 발급하지 않는다 — 응답할 때 생성일과 ID로 만든다(#413).
    Report report = reportRepository.save(Report.createPending(
        userId, request.productId(), claim.getId(), request.accidentType(), request.offeredAmount(),
        request.question()));

    // 첨부 저장과 OCR 발행을 두 패스로 나눈다(문서마다 번갈아 저장하지 않는다) — 같은 타입 INSERT으로 JDBC 배치 활용
    List<ReportAttachment> attachments = new ArrayList<>(docTotal);

    for (CreateReportRequest.Document document : documents) {
      attachments.add(reportAttachmentRepository.save(ReportAttachment.of(
          report.getId(), document.name(), document.s3Url(),
          document.fileType(), document.reportType())));
    }

    // document 1건당 OCR 트리거 발행 (doc_index 1-based, doc_total로 FastAPI가 OCR 완료(fan-in) 판별)
    for (int i = 0; i < documents.size(); i++) {

      CreateReportRequest.Document document = documents.get(i);

      ReportAttachment attachment = attachments.get(i);

      ocrJobOutboxPort.enqueue(new OcrJob(
          UUID.randomUUID().toString(),
          toS3Key(document.s3Url()),
          attachment.getMimeType(),
          userId.toString(),
          document.reportType(),
          claim.getId().toString(),
          report.getId().toString(),
          attachment.getId().toString(),
          i + 1,
          docTotal,
          Instant.now().toString()));
    }

    persistSample.stop(persistTimer);

    return CreateReportResponse.from(report);
  }

  /**
   * PATCH /reports/{reportId}/proposals/{proposalId} — 본인 리포트의 특정 제안 상담 수락(ACCEPTED).
   *
   * <p>이 엔드포인트는 "상담 수락 전용"이다. 최종 채택(제안 ACCEPTED·리포트 CLOSED)은 채팅방 화면의
   * PATCH /chats/{chatRoomId}/accept에서만 하고, 거절은 PATCH /chats/{chatRoomId}/reject에서만 한다.
   *
   * <p><b>요청 status=ACCEPTED가 응답 review_status=COUNSELING을 만드는 이유</b>: 요청값 ACCEPTED는
   * "제안을 ACCEPTED로 바꿔라"가 아니라 "이 사정사와 상담을 수락한다(=채팅방을 연다)"는 뜻이다. 그
   * 결과로 제안은 실제 도메인 전이인 SENT→COUNSELING을 밟으므로 응답에는 COUNSELING이 담긴다.
   * 요청 문자열과 응답 상태값이 다른 건 의도된 계약이다(프론트 화면 흐름에 맞춘 명명).
   */
  public ProposalDecisionResponse decide(UUID userId, UUID reportId, UUID proposalId, boolean startsCounseling) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    ReportReview review = reportReviewRepository.findById(proposalId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
    if (!review.belongsTo(reportId)) {
      throw new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND);
    }

    UUID chatRoomId = startsCounseling ? startCounseling(report, review) : null;

    return new ProposalDecisionResponse(
        reportId, review.getId(), review.getAdjusterId(), report.getStatus(), review.getStatus(), chatRoomId);
  }

  /**
   * 상담 시작(제안 "상담 수락"): 제안 SENT→COUNSELING, 리포트 →COUNSELING 전이를 먼저 끝내고 그 다음
   * chat 도메인에 방 개설을 위임한다. 순서가 중요하다 — 던질 수 있는 검증(제안 종료 상태·리포트 전이표
   * 위반)을 방 INSERT 앞에 모아 고아 방이 생기지 않게 한다(ReportReviewCommandService의 스켈레톤 순서와
   * 같은 사고방식). 방이 새로 열렸을 때만 사정사에게 상담 요청 알림을 발행한다(재요청 스팸 방지).
   */
  private UUID startCounseling(Report report, ReportReview review) {
    review.startCounseling();
    report.applyReviewTransition(ReportStatus.COUNSELING);

    ConsultationRoomResult room = consultationRoomOpener.openConsultationRoom(
        report.getUserId(), review.getAdjusterId(), report.getId(), review.getId());

    if (room.created()) {
      eventPublisher.publishEvent(new ConsultationRequestedEvent(
          review.getAdjusterId(), report.getUserId(), report.getId(), review.getId(), room.chatRoomId()));
    }
    return room.chatRoomId();
  }

  private String toS3Key(String s3Url) {
    if (!StringUtils.hasText(s3Url)) {
      return null;
    }
    try {
      String path = URI.create(s3Url).getPath();
      return path == null ? s3Url : path.replaceFirst("^/", "");
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
