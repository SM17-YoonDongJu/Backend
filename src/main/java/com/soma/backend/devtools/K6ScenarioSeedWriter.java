package com.soma.backend.devtools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;

/**
 * {@link K6ScenarioSeedRunner}의 쓰기 트랜잭션 협력자. 시딩 단위(= 리포트 1건과 그 자식들) 하나가
 * 이 클래스의 {@code @Transactional} public 메서드 한 번에 대응한다.
 *
 * <p><b>왜 러너와 별도 Bean인가(self-invocation 회피).</b> {@code ApplicationRunner.run()} 안에서
 * 자기 자신의 {@code @Transactional} private 메서드를 부르면 Spring AOP 프록시를 타지 않아 트랜잭션이
 * 조용히 열리지 않는다. 그러면 {@code report.applyReviewStart()} 같은 dirty-checking 전이가 UPDATE 없이
 * 사라져 "상태만 안 바뀐" 시드가 만들어진다(가장 찾기 어려운 종류의 버그다). 레포에 같은 이유로 분리된
 * Bean이 이미 3개 있다 — {@code ReportReviewSkeletonInitializer}, {@code ChatRoomInsertOperator},
 * {@code TerminalFailureJournalReader}.
 *
 * <p><b>{@code REQUIRES_NEW}를 쓰지 않는다.</b> {@code ChatRoomInsertOperator}가 그 전파를 쓰는 이유는
 * "동시 최초 개설의 UNIQUE 충돌을 호출자 트랜잭션에서 격리"하기 위함인데, 이 시더는 부트타임 단일
 * 스레드라 경쟁이 없다. 그래서 {@code ChatRoomInsertOperator}·{@code ReportReviewSkeletonInitializer}를
 * 호출하지 않고 리포지토리로 직접 저장한다(커넥션 이중 점유·aborted 세션 커밋 함정 회피).
 *
 * <p><b>한 트랜잭션에 여러 Aggregate를 담는 근거.</b> D 풀 1건은 UserClaim·Report·ReportReview·ChatRoom·
 * ChatMessage 5개 Aggregate를 한 커밋으로 만든다. 운영 코드가 이미 정확히 같은 조합을 한 트랜잭션으로
 * 처리하고({@code ReportCommandService.startCounseling} → {@code ChatRoomCommandService}), "한 트랜잭션 =
 * 한 Aggregate" 원칙의 목적은 동시성 하의 불변식 보호인데 부트타임 픽스처 로딩에는 경쟁이 없다.
 * Aggregate별로 쪼개면 중간 실패 시 "방은 있는데 리포트는 채택 대기"처럼 k6가 풀을 오분류하는 상태가 남는다.
 *
 * <p><b>raw SQL은 {@link #patchAiDraft} 한 곳뿐이다.</b> {@code DevMockDataSeedRunner}가 명문화한 두 조건을
 * 모두 충족한다 — (1) 대상 컬럼에 PII 암호화 대상이 하나도 없고(암호화는 {@code reports.question}뿐이며
 * 그건 팩터리가 처리한다) (2) 엔티티에 이 컬럼들을 쓰는 정식 경로가 아예 없다(운영에서도 AI 워커가 raw
 * SQL로 UPDATE한다). {@code DevMockDataSeedRunner}가 필요로 했던 나머지 raw SQL은 이번엔 전부 불필요하다 —
 * 백데이트가 필요 없고(시드는 생성 시각 그대로 쓴다), COUNSELING·방 개설 조합을 도메인 메서드로 그대로
 * 만들 수 있기 때문이다.
 *
 * <p>{@code @Profile}을 {@link K6ScenarioSeedRunner}와 동일하게 맞춘다 — 호출자(러너)가 이미 prod에서
 * 빈으로 등록되지 않으므로 실행 경로는 없지만, 33,700행을 실제로 쓰는 이 컴포넌트 자체가 prod 컨텍스트에
 * 남아 있지 않게 해서 "프로파일로 이중 차단"이라는 서술을 쓰기 주체에도 그대로 적용한다.
 */
@Component
@Profile("!test & !prod")
public class K6ScenarioSeedWriter {

  /** case_no 접두어에 들어가는 풀 코드. 멱등 체크·사후 정리가 이 문자 하나로 풀을 식별한다. */
  static final String POOL_PROPOSAL = "P";

  static final String POOL_CONSULT = "D";

  static final String POOL_DURABLE = "M";

  static final String POOL_INSPECTION = "I";

  /**
   * M 풀 방에 미리 넣는 TEXT 메시지 수. {@code ChatMessageController}의 기본 페이지 크기가 30이라, SYSTEM
   * 1건을 더해도 30 이하면 첫 조회에서 커서 WHERE 절이 한 번도 실행되지 않는다(전량이 한 페이지에 담겨
   * {@code has_next=false}). 40으로 잡아 총 41건 — 기본 호출로도 2페이지 이상 나오게 한다.
   */
  private static final int DURABLE_TEXT_MESSAGE_COUNT = 40;

  private static final String SYNTHETIC_QUESTION =
      "k6 부하테스트 합성 데이터입니다. 이번 사고로 보험금 청구가 가능한지, 가능하다면 어느 정도 금액이 나오는지 확인하고 싶습니다.";

  private static final String SYNTHETIC_DESCRIPTION =
      "k6 부하테스트 합성 데이터입니다. 실제 사고 경위가 아니라 부하 측정을 위해 생성된 더미 서술입니다.";

  private static final String SYNTHETIC_ADDITIONAL_INFORMATION =
      "k6 부하테스트 합성 데이터입니다. 추가 안내 사항 자리에 들어가는 더미 문장입니다.";

  private static final List<String> SYNTHETIC_DIAGNOSIS = List.of("k6 합성 진단명");

  private static final String SYNTHETIC_REVIEW =
      "k6 부하테스트 합성 검수 의견입니다.";

  private static final String ROOM_OPENED_MESSAGE =
      "상담 채팅방이 열렸습니다. 손해사정사와 상담을 시작해보세요.";

  /** 사고일. 미채택 스윕(7일)은 {@code reports.created_at} 기준이라 이 값과 무관하다. */
  private static final int ACCIDENT_DAYS_AGO = 7;

  private static final int CLAIM_OFFERED_AMOUNT = 1_500_000;

  // AI 초안(§5.3) — applicable_guarantees가 non-null이어야 Report.isAiDraftGenerated()가 true가 되고
  // GET /reports/{id}/analysis-status가 COMPLETED로 응답한다. 비워두면 영구 PROCESSING이라 카드/상세
  // 페이로드가 실제보다 작아져 부하 측정치가 낙관적으로 왜곡된다.
  private static final String AI_TITLE = "k6 부하테스트 리포트";

  private static final int AI_CLAIMED_MIN_AMOUNT = 1_000_000;

  private static final int AI_CLAIMED_MAX_AMOUNT = 3_000_000;

  private static final int AI_OFFERED_AMOUNT = 1_500_000;

  private static final List<String> AI_GUARANTEES = List.of("상해입원의료비");

  private static final List<String> AI_OMITTED = List.of("k6 합성 미청구 특약");

  private static final List<String> AI_PRECEDENTS = List.of("k6 합성 약관·판례 근거");

  private static final String AI_TREATMENT = "k6 부하테스트 합성 AI 소견입니다.";

  private static final String AI_CONFIDENCE_LEVEL = "high";

  // 사정사 제안 내용(§5.3) — GET /reports/{id}/proposals의 proposalSummary가 report_reviews.review 원문이다.
  private static final int REVIEW_ESTIMATE_MIN_AMOUNT = 1_200_000;

  private static final int REVIEW_ESTIMATE_MAX_AMOUNT = 2_800_000;

  private final UserClaimRepository userClaimRepository;
  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final JdbcTemplate jdbcTemplate;

  public K6ScenarioSeedWriter(
      UserClaimRepository userClaimRepository,
      ReportRepository reportRepository,
      ReportReviewRepository reportReviewRepository,
      ChatRoomRepository chatRoomRepository,
      ChatMessageRepository chatMessageRepository,
      JdbcTemplate jdbcTemplate) {
    this.userClaimRepository = userClaimRepository;
    this.reportRepository = reportRepository;
    this.reportReviewRepository = reportReviewRepository;
    this.chatRoomRepository = chatRoomRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * P 풀 — 제안 decide({@code PATCH /reports/{id}/proposals/{pid}}) 소비용. 리포트 1건에 사정사 전원의
   * SENT 제안을 달고 방은 만들지 않는다. {@code UNIQUE (report_id, adjuster_id)} 때문에 리포트당 제안은
   * 사정사 수(10)가 상한이다 — 그래서 제안 6,000건에 리포트 6,000건이 아니라 600건이면 된다.
   *
   * <p>이 풀의 리포트는 모든 k6 사정사의 {@code GET /reports/pending-review}에서 사라진다(자기 제안이
   * SENT면 목록에서 제외되기 때문). 사정사 목록 시나리오는 I 풀이 담당한다.
   */
  @Transactional
  public void seedProposalReport(UUID userId, int userSeq, int idx, List<UUID> adjusterIds) {
    AccidentType accidentType = accidentTypeOf(idx);
    UUID claimId = createClaim(userId, accidentType);
    Report report = createReport(userId, claimId, accidentType, POOL_PROPOSAL, userSeq, idx);

    for (UUID adjusterId : adjusterIds) {
      ReportReview review = new ReportReview(report.getId(), adjusterId);
      review.updateReviewContent(REVIEW_ESTIMATE_MIN_AMOUNT, REVIEW_ESTIMATE_MAX_AMOUNT,
          AI_GUARANTEES, List.of(), AI_PRECEDENTS, SYNTHETIC_REVIEW);
      reportReviewRepository.save(review);
    }
    report.applyReviewStart();

    finish(report.getId());
  }

  /**
   * D 풀 — 상담 수락/거절({@code PATCH /chats/{id}/accept}·{@code /reject}) 소비용. 리포트당 방 1개로
   * 만든다. 그래야 accept(리포트 전체 소모: 형제 제안 REJECTED + 형제 방 CLOSED)와 reject(방 1개 소모)의
   * 소모 단위가 같아져 k6가 둘을 자유롭게 섞을 수 있고, reject가 리포트 행에 거는 {@code PESSIMISTIC_WRITE}
   * 락 경합도 사라진다.
   *
   * <p>목표 상태는 {@code ReportCommandService.startCounseling} 직후 스냅샷이다 — review COUNSELING +
   * report COUNSELING + room ACTIVE.
   */
  @Transactional
  public void seedConsultRoom(UUID userId, int userSeq, int idx, UUID adjusterId) {
    AccidentType accidentType = accidentTypeOf(idx);
    UUID claimId = createClaim(userId, accidentType);
    Report report = createReport(userId, claimId, accidentType, POOL_CONSULT, userSeq, idx);

    ReportReview review = new ReportReview(report.getId(), adjusterId);
    review.updateReviewContent(REVIEW_ESTIMATE_MIN_AMOUNT, REVIEW_ESTIMATE_MAX_AMOUNT,
        AI_GUARANTEES, List.of(), AI_PRECEDENTS, SYNTHETIC_REVIEW);
    reportReviewRepository.save(review);

    report.applyReviewStart();
    review.startCounseling();
    report.applyReviewTransition(ReportStatus.COUNSELING);

    ChatRoom room = chatRoomRepository.save(
        ChatRoom.openConsultation(userId, adjusterId, report.getId(), review.getId()));
    ChatMessage opened = chatMessageRepository.save(ChatMessage.system(room.getId(), ROOM_OPENED_MESSAGE));
    room.touchLastMessage(ROOM_OPENED_MESSAGE, messageTimeOf(opened));

    finish(report.getId());
  }

  /**
   * M 풀 — 메시지 전송·채팅 조회({@code POST /chats/{id}/messages}, {@code GET /chats*}) 소비용.
   * {@code ChatConsultationCommandService.accept} 완료 직후 스냅샷을 그대로 재현한다 — review ACCEPTED +
   * report CLOSED + <b>room ACTIVE</b>(accept는 내 방을 닫지 않는다).
   *
   * <p>이 조합을 고른 이유: (1) 방이 ACTIVE라 메시지 전송이 되고, (2) accept/reject를 호출하면
   * {@code ensureDecidable}에서 409로 튕겨 <b>구조적으로 소모될 수 없으며</b>, (3) {@code GET /chats}
   * 응답의 {@code review_status == "ACCEPTED"}로 k6가 API만 보고 D 풀과 구분할 수 있다.
   */
  @Transactional
  public void seedDurableRoom(UUID userId, int userSeq, int idx, UUID adjusterId) {
    AccidentType accidentType = accidentTypeOf(idx);
    UUID claimId = createClaim(userId, accidentType);
    Report report = createReport(userId, claimId, accidentType, POOL_DURABLE, userSeq, idx);

    ReportReview review = new ReportReview(report.getId(), adjusterId);
    review.updateReviewContent(REVIEW_ESTIMATE_MIN_AMOUNT, REVIEW_ESTIMATE_MAX_AMOUNT,
        AI_GUARANTEES, List.of(), AI_PRECEDENTS, SYNTHETIC_REVIEW);
    reportReviewRepository.save(review);

    report.applyReviewStart();
    review.accept();
    report.accept(adjusterId);

    ChatRoom room = chatRoomRepository.save(
        ChatRoom.openConsultation(userId, adjusterId, report.getId(), review.getId()));
    chatMessageRepository.save(ChatMessage.system(room.getId(), ROOM_OPENED_MESSAGE));

    String lastContent = ROOM_OPENED_MESSAGE;
    ChatMessage lastMessage = null;
    for (int no = 1; no <= DURABLE_TEXT_MESSAGE_COUNT; no++) {
      UUID senderId = (no % 2 == 1) ? userId : adjusterId;
      lastContent = textMessageContent(no);
      lastMessage = chatMessageRepository.save(ChatMessage.text(room.getId(), senderId, lastContent));
    }
    room.touchLastMessage(lastContent, messageTimeOf(lastMessage));

    finish(report.getId());
  }

  /**
   * I 풀 — 사정사 검수대기({@code GET /reports/pending-review}, {@code GET /reports/{id}/review},
   * {@code PATCH /reports/{id}}) 소비용. 제안도 방도 만들지 않아 {@code AWAITING_INSPECTION} 그대로 남는다.
   *
   * <p>{@code findPendingReviewRows}가 "요청 사정사의 제안이 SENT·COUNSELING인 리포트"를 제외하므로,
   * 제안이 하나도 없는 이 풀이 없으면 사정사 목록 시나리오가 빈 응답만 받는다.
   */
  @Transactional
  public void seedInspectionReport(UUID userId, int userSeq, int idx) {
    AccidentType accidentType = accidentTypeOf(idx);
    UUID claimId = createClaim(userId, accidentType);
    Report report = createReport(userId, claimId, accidentType, POOL_INSPECTION, userSeq, idx);

    finish(report.getId());
  }

  /**
   * 사고 유형을 idx로 분산한다 — 0,1: 실손 / 2,3: 교통 / 4: 기타. k6 사정사의 전문분야가
   * {@code ["실손보험","교통사고"]}라 매칭 80% + 미매칭 20%가 되어, 검수대기 정렬의 전문분야 CASE 분기
   * 양쪽이 모두 실행된다(전부 매칭이면 한쪽 분기가 부하테스트에서 아예 안 돌아간다).
   */
  private static AccidentType accidentTypeOf(int idx) {
    return switch (idx % 5) {
      case 0, 1 -> AccidentType.MEDICAL_INDEMNITY;
      case 2, 3 -> AccidentType.TRAFFIC;
      default -> AccidentType.OTHER;
    };
  }

  /**
   * case_no는 {@code nextCaseNoSequence}(INSERT..ON CONFLICT..RETURNING)를 쓰지 않고 직접 만든다 —
   * (1) 5,100번의 DB 왕복이 사라지고, (2) 당일 운영 카운터를 소모하지 않아 부하테스트 중 실제
   * {@code POST /reports}의 사건번호가 이어서 발급되며, (3) {@code case_no LIKE 'K6-%'} 하나로 시드 전량을
   * 식별할 수 있어 멱등 체크와 사후 정리가 자명해진다. {@code case_no}는 {@code varchar(100) UNIQUE}이고
   * 포맷을 파싱하는 코드가 레포에 없다(표시용).
   */
  static String caseNo(String pool, int userSeq, int idx) {
    return String.format(Locale.ROOT, "K6-%s-%04d-%03d", pool, userSeq, idx);
  }

  /** (유저, 풀) 단위 멱등 카운트용 LIKE 패턴. idx가 0-padding 3자리라 접두어가 모호하지 않다. */
  static String caseNoPrefixPattern(String pool, int userSeq) {
    return String.format(Locale.ROOT, "K6-%s-%04d-%%", pool, userSeq);
  }

  private UUID createClaim(UUID userId, AccidentType accidentType) {
    ClaimDetails details = ClaimDetails.of(accidentType, SYNTHETIC_DIAGNOSIS, List.of());
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        userId, null, CLAIM_OFFERED_AMOUNT, LocalDate.now().minusDays(ACCIDENT_DAYS_AGO),
        accidentType, details, SYNTHETIC_QUESTION, SYNTHETIC_DESCRIPTION, SYNTHETIC_ADDITIONAL_INFORMATION));
    return claim.getId();
  }

  private Report createReport(UUID userId, UUID claimId, AccidentType accidentType,
      String pool, int userSeq, int idx) {
    return reportRepository.save(Report.createPending(
        userId, null, claimId, accidentType, SYNTHETIC_QUESTION, caseNo(pool, userSeq, idx)));
  }

  private static String textMessageContent(int no) {
    return "k6 부하테스트 합성 대화 " + no + "번째 메시지입니다. 채팅 목록 미리보기와 커서 페이지네이션 경로를 "
        + "실제 페이로드 크기로 재현하기 위해 넣어 둔 더미 문장입니다.";
  }

  private static LocalDateTime messageTimeOf(ChatMessage message) {
    // @CreatedDate는 persist 시점(@PrePersist)에 채워지므로 save 직후 읽을 수 있다. 방어적으로만 폴백한다.
    return (message == null || message.getCreatedAt() == null) ? LocalDateTime.now() : message.getCreatedAt();
  }

  /**
   * 시딩 단위를 마무리한다 — <b>순서가 중요하다.</b> 먼저 영속성 컨텍스트를 flush해 INSERT와 상태 전이
   * UPDATE를 DB에 반영한 다음에야 {@link #patchAiDraft}의 raw UPDATE를 날린다.
   *
   * <p>순서를 뒤집으면 두 가지가 모두 깨진다. (1) raw UPDATE가 아직 INSERT되지 않은 행을 찾아 0행 갱신이
   * 된다(JdbcTemplate은 Hibernate의 flush-before-query 대상이 아니다). (2) 설령 행이 있어도, Hibernate는
   * {@code @DynamicUpdate}가 없으면 <b>전 컬럼</b>을 쓰는 UPDATE를 만들므로 이후 커밋 flush가 방금 채운 AI
   * 초안 컬럼을 엔티티의 null 값으로 덮어쓴다. flush 이후에는 엔티티를 더 건드리지 않으므로(스냅샷 == 현재
   * 상태) 커밋 시점에 추가 UPDATE가 생기지 않는다.
   */
  private void finish(UUID reportId) {
    reportRepository.flush();
    patchAiDraft(reportId);
  }

  /**
   * REPORTS의 AI 초안 컬럼을 채운다(문서화된 raw SQL 예외). 운영에서는 AI 워커가 OCR·분석 후 이 컬럼들을
   * 직접 SQL로 갱신하며 Spring 엔티티에는 쓰기 경로가 없다. 이 러너가 만드는 리포트는 OCR 트리거를 발행하지
   * 않아 AI 워커가 절대 채워주지 않으므로 여기서 직접 채운다. 대상 컬럼 중 PII 암호화 대상은 없다
   * ({@code question}만 암호화 대상이고 그건 {@code Report.createPending}이 컨버터로 처리한다).
   *
   * <p>{@code DevMockDataSeedRunner.patchAiDraft}와 달리 {@code title}도 함께 쓴다 — 카드 목록 응답에
   * 실리는 값이라 비워두면 목록 페이로드가 실제와 달라진다.
   *
   * <p>테이블명에 스키마를 붙이지 않는다 — dev/prod는 JDBC URL {@code ?currentSchema=core,public}이,
   * test_db는 {@code public}이 해석한다. DDL이 아니라 DML이라 "신규 테이블은 {@code core.} 명시" 규칙과 무관하다.
   */
  private void patchAiDraft(UUID reportId) {
    jdbcTemplate.update(
        "UPDATE reports SET title=?, claimed_min_amount=?, claimed_max_amount=?, offered_amount=?, "
            + "applicable_guarantees=?, omitted_special_contract=?, basis_terms_precedents=?, "
            + "treatment=?, confidence_level=?, is_masked=false WHERE id=?",
        ps -> {
          ps.setString(1, AI_TITLE);
          ps.setObject(2, AI_CLAIMED_MIN_AMOUNT);
          ps.setObject(3, AI_CLAIMED_MAX_AMOUNT);
          ps.setObject(4, AI_OFFERED_AMOUNT);
          ps.setArray(5, ps.getConnection().createArrayOf("text", AI_GUARANTEES.toArray()));
          ps.setArray(6, ps.getConnection().createArrayOf("text", AI_OMITTED.toArray()));
          ps.setArray(7, ps.getConnection().createArrayOf("text", AI_PRECEDENTS.toArray()));
          ps.setString(8, AI_TREATMENT);
          ps.setString(9, AI_CONFIDENCE_LEVEL);
          ps.setObject(10, reportId);
        });
  }
}
