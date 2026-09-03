package com.soma.backend.domain.report.service;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.service.ChatRoomCommandService;
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
@RequiredArgsConstructor
@Transactional
public class ReportCommandService {

  private static final DateTimeFormatter CASE_NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
  // 한 리포트의 첨부 문서 수 상한. 무제한이면 요청 하나가 한 트랜잭션의 INSERT 수와 case_no 카운터 락
  // 점유 시간을 무한정 늘려(로컬 DoS) 같은 날짜의 다른 생성까지 막는다.
  private static final int MAX_DOCUMENTS = 20;

  private final UserClaimRepository userClaimRepository;
  private final ReportRepository reportRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final OcrJobOutboxPort ocrJobOutboxPort;
  private final ChatRoomCommandService chatRoomCommandService;
  private final ApplicationEventPublisher eventPublisher;

  /** POST /reports — 사고 정보 입력 수신 → 저장 → OCR 트리거 발행. 202(비동기). */
  public CreateReportResponse createReport(UUID userId, CreateReportRequest request) {

    if (request.accidentType() == null || request.accidentDate() == null) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }

    // 문서 수 상한은 DB 쓰기·case_no 카운터 락 획득 전에 검증한다 — 초과 요청이 카운터를 증가시켰다
    // 롤백해 사건번호에 구멍을 내지 않도록 fail-fast 한다.
    List<CreateReportRequest.Document> documents =
        request.documents() == null ? List.of() : request.documents();
    if (documents.size() > MAX_DOCUMENTS) {
      throw new BusinessException(ErrorCode.REPORT_TOO_MANY_DOCUMENTS);
    }
    int docTotal = documents.size();

    //accidentType에 따른 claimDetails 생성
    ClaimDetails details =
        ClaimDetails.of(request.accidentType(), request.diagnosis(), request.hospitalizations());

    //DB 저장
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        userId, request.productId(), request.offeredAmount(), request.accidentDate(),
        request.accidentType(), details, request.question(), request.description(),
        request.additionalInformation()));

    //Reports 테이블 스켈레톤 저장
    Report report = reportRepository.save(Report.createPending(
        userId, request.productId(), claim.getId(), request.accidentType(), request.question(),
        generateCaseNo()));

    // 첨부 저장과 OCR 발행을 두 패스로 나눈다(문서마다 번갈아 저장하지 않는다) — 같은 타입 INSERT가
    // 연속돼야 JDBC 배치로 묶여 커밋 flush의 DB 왕복이 준다. 이 flush는 case_no 카운터 락 구간 안에서
    // 일어나므로, 왕복을 줄이면 동시 생성의 직렬화 대기도 그만큼 짧아진다.
    List<ReportAttachment> attachments = new ArrayList<>(docTotal);
    for (CreateReportRequest.Document document : documents) {
      attachments.add(reportAttachmentRepository.save(ReportAttachment.of(
          report.getId(), document.name(), document.s3Url(),
          toContentType(document.fileType()), document.reportType())));
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
  public ProposalDecisionResponse decide(UUID userId, UUID reportId, UUID proposalId, String status) {
    boolean startsCounseling = parseDecision(status);

    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    ReportReview review = reportReviewRepository.findById(proposalId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
    if (!review.getReportId().equals(reportId)) {
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

    ConsultationRoomResult room = chatRoomCommandService.openConsultationRoom(
        report.getUserId(), review.getAdjusterId(), report.getId(), review.getId());

    if (room.created()) {
      eventPublisher.publishEvent(new ConsultationRequestedEvent(
          review.getAdjusterId(), report.getUserId(), report.getId(), review.getId(), room.chatRoomId()));
    }
    return room.chatRoomId();
  }

  /**
   * ACCEPTED(상담 수락 — 채팅방 개설) 또는 REJECTED(현재 아무 동작 없음, 거절은
   * PATCH /chats/{chatRoomId}/reject 전용)만 허용한다. 반환값은 상담 수락 여부(true=ACCEPTED)다.
   * ReviewStatus enum을 재사용하지 않는 이유: 이 요청값은 더 이상 REPORT_REVIEWS.status와
   * 1:1 대응하지 않는다(ACCEPTED 요청 → 실제로는 review가 COUNSELING이 된다).
   */
  private boolean parseDecision(String status) {
    if (!StringUtils.hasText(status)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if ("ACCEPTED".equals(status)) {
      return true;
    }
    if ("REJECTED".equals(status)) {
      return false;
    }
    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
  }

  /**
   * 사람용 사건번호 yyyyMMdd-NNN. 당일 시퀀스는 DB 원자 카운터로 발급해 동시 생성 경합(case_no UNIQUE
   * 위반)을 막는다. 다만 이 카운터 행 락은 트랜잭션 커밋까지 유지돼 같은 날짜 동시 생성이 직렬화된다 —
   * 첨부·아웃박스 INSERT를 배치로 묶고 문서 수를 제한해 락 구간을 줄였다. 카운터 자체의 구조 개편
   * (SEQUENCE·독립 트랜잭션)은 부하 측정 후 #266에서 다룬다.
   */
  private String generateCaseNo() {
    LocalDate today = LocalDate.now();
    int sequence = reportRepository.nextCaseNoSequence(today);
    return String.format("%s-%03d", today.format(CASE_NO_DAY), sequence);
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

  private String toContentType(String fileType) {
    if (!StringUtils.hasText(fileType)) {
      return null;
    }
    String normalized = fileType.toLowerCase().replaceFirst("^\\.", "");
    return switch (normalized) {
      case "pdf" -> "application/pdf";
      case "jpg", "jpeg" -> "image/jpeg";
      case "png" -> "image/png";
      case "tiff", "tif" -> "image/tiff";
      default -> null;
    };
  }
}
