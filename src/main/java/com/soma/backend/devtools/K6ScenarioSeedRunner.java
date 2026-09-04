package com.soma.backend.devtools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * k6 부하테스트용 시나리오 데이터({@code k6-user-1}~{@code N}의 리포트·제안·채팅방·메시지)를 1회성으로
 * 시딩하는 임시 러너. dev에서 {@code POST /auth/dev/login}이 닉네임만으로 토큰을 내주므로, 100 RPS 부하의
 * 각 시나리오가 소비할 데이터를 미리 채워 둔다. 로드테스트 창이 끝나면 후속 커밋에서 이 클래스와 시딩된
 * 데이터를 함께 정리한다(정리 SQL은 아래 「사후 정리」 참조).
 *
 * <h2>왜 {@code @Profile}을 선례보다 강하게 잡았나</h2>
 * {@code K6AdjusterSeedRunner}·{@code DevMockDataSeedRunner}는 {@code @Profile("!test")}인데 이 러너만
 * {@code "!test & !prod"}다. 폭발 반경이 다르기 때문이다 — 사정사 러너가 만드는 건 20행이지만 이 러너는
 * 기본 설정에서 <b>약 33,700행</b>(reports 5,100 / report_reviews 11,800 / chatroom 3,800 /
 * chatroom_messages 7,800 / user_claims 5,100 / users 100)을 만든다. 프로퍼티 오주입 한 번으로 운영 DB가
 * 오염되지 않게 프로파일로도 이중 차단한다. 의도적인 편차다.
 *
 * <h2>게이트 프로퍼티</h2>
 * <b>어떤 {@code application*.yml}에도 선언하지 않는다</b>(선례 {@code app.dev-seed.enabled}·
 * {@code app.dev-seed.k6-adjusters-enabled}와 동일). 아래 6개는 생성자 {@code @Value} 기본값으로만 존재하고,
 * 필요할 때 compose {@code environment:}로 주입해 켠다({@code deploy/docker-compose.dev.yml}에 키를 명시
 * 열거하지 않으면 컨테이너에 전달되지 않아 러너가 조용히 아무것도 하지 않는다).
 *
 * <pre>
 * app.dev-seed.k6-scenarios-enabled            false  마스터 게이트
 * app.dev-seed.k6-user-count                     100  k6-user-1 ~ k6-user-N (k6 VU와 1:1 매핑 전제)
 * app.dev-seed.k6-proposal-reports-per-user        8  P 풀 — 리포트당 SENT 제안 10건, 방 없음
 * app.dev-seed.k6-consult-rooms-per-user          36  D 풀 — review/report COUNSELING + room ACTIVE
 * app.dev-seed.k6-durable-rooms-per-user           2  M 풀 — review ACCEPTED + report CLOSED + room ACTIVE
 * app.dev-seed.k6-inspection-reports-per-user      5  I 풀 — AWAITING_INSPECTION, 제안 0건
 * </pre>
 *
 * <p><b>env 키 이름 주의.</b> yml에 선언하지 않으므로 {@code DEV_LOGIN_ENABLED}처럼 yml의 {@code ${...}}
 * 치환을 거치지 않는다 — Spring이 프로퍼티명에서 직접 env 키를 유도하기 때문에 {@code APP_} 접두어가
 * 반드시 붙는다({@code app.dev-seed.k6-scenarios-enabled} → {@code APP_DEV_SEED_K6_SCENARIOS_ENABLED}).
 * 접두어를 빼면 바인딩이 조용히 실패해 러너가 아무것도 하지 않는다.
 *
 * <p>10분(600초) × 100 RPS + 여유율 20% 기준 수량이다. 5분 테스트로 줄이려면
 * {@code k6-proposal-reports-per-user: 4}, {@code k6-consult-rooms-per-user: 18}로 정확히 절반이 된다
 * (프로퍼티만 바꾸면 되고 코드는 그대로다).
 *
 * <h2>트랜잭션·부작용</h2>
 * {@code @Transactional}을 붙이지 않는다 — 3.4만 행을 한 트랜잭션으로 묶으면 1건 실패에 전부 롤백되고,
 * 재실행 시 진행분을 재활용할 수 없으며, 수 분간 커넥션을 점유한다. 쓰기는 시딩 단위(리포트 1건과 그
 * 자식들)마다 {@link K6ScenarioSeedWriter}의 {@code @Transactional} 메서드로 커밋한다(자가 호출은 AOP
 * 프록시를 안 타 트랜잭션이 조용히 안 열리므로 반드시 별도 Bean이어야 한다).
 *
 * <p>도메인 서비스를 하나도 호출하지 않으므로 도메인 이벤트가 발행되지 않는다 — <b>OCR 트리거(SQS) 0건</b>
 * (그래서 AI 비용·워커 부하가 발생하지 않는다), 제안·상담 푸시 0건, Redis 브로드캐스트 0건. 부수효과로
 * k6 유저의 알림함은 비어 있다(알림 조회를 부하 시나리오에 넣을 계획이면 별도 시딩이 필요하다).
 *
 * <h2>멱등성</h2>
 * 체크 단위는 (유저 × 풀)이다. {@code SELECT count(*) FROM reports WHERE case_no LIKE 'K6-P-0007-%'}로
 * 이미 만들어진 수를 세고 부족분만 idx를 이어 붙여 생성한다. 유저 단위 try/catch라 한 풀에서 실패하면 그
 * 유저의 남은 풀을 건너뛰므로 idx에 구멍이 생기지 않고, 다음 실행이 정확히 이어서 채운다. 최종 방어선은
 * DB 제약({@code reports.case_no} UNIQUE, {@code report_reviews (report_id, adjuster_id)} UNIQUE)이다.
 *
 * <p><b>전제 — 연속성이 이 러너 밖에서 깨지지 않아야 한다.</b> 카운트 기반 재개는 "1..existing이 연속"을
 * 가정한다. 이 러너 자신이 실패하는 경로(유저 단위 try/catch)는 항상 뒤쪽 idx만 비우므로 이 전제를 지킨다.
 * 다만 외부에서 중간 idx의 시드 리포트만 골라 지우는 등 연속성이 깨지면, 그다음 idx 생성 시도가
 * {@code case_no} UNIQUE 위반으로 실패해 그 (유저,풀)이 재실행으로도 복구되지 않는다 — 정상적인 실행
 * 중단·재실행 흐름에서는 발생하지 않는다.
 *
 * <p>{@code JdbcTemplate} 카운트는 쿼리 규칙 위반이 아니다 — "조회에 native query 금지"는 도메인
 * 리포지토리 패키지에 대한 규칙이고, {@code devtools}의 1회성 러너에서 쓰는 {@code JdbcTemplate}는
 * 선례 2건이 이미 확립한 관례다. 덕분에 프로덕션 리포지토리에 시더 전용 파생 쿼리를 남기지 않는다.
 *
 * <h2>실행 전 사전 점검</h2>
 * <ol>
 *   <li>사정사 시딩({@code APP_DEV_SEED_K6_ADJUSTERS_ENABLED=true})을 먼저 1회 돌린다. 없으면 이 러너는
 *       fail-fast로 아무것도 만들지 않는다.</li>
 *   <li>{@code APP_DEV_SEED_K6_USER_COUNT=3}으로 소규모 리허설을 돌려 로그의 총 경과시간으로 1유저당
 *       소요 시간을 잰다.</li>
 *   <li>{@code (1유저 소요) × 유저수 + 90s < 180s}인지 확인한다. 시딩 중에는
 *       {@code /actuator/health/readiness}가 UP이 아니고({@code ApplicationRunner}는
 *       {@code ApplicationReadyEvent} 이전에 돈다) dev compose 헬스체크가 {@code start_period 90s} +
 *       {@code 30s × 3}이라 180초를 넘기면 컨테이너가 unhealthy로 마킹된다. 넘으면 풀별 수량을 줄이거나
 *       시딩 창에 한해 {@code start_period}를 임시로 올린다.</li>
 *   <li>본 시딩 실행 → 요약 로그 확인 → 게이트를 {@code false}로 되돌리고 재기동한다.</li>
 * </ol>
 *
 * <h2>사후 정리(로드테스트 창 종료 후)</h2>
 * FK 때문에 순서가 중요하다. 전부 {@code case_no LIKE 'K6-%'} / {@code nickname LIKE 'k6-%'} 기준이다.
 * <pre>
 * DELETE FROM core.chatroom_messages
 *  WHERE room_id IN (SELECT id FROM core.chatroom
 *                     WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%'));
 * DELETE FROM core.chatroom         WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%');
 * DELETE FROM core.report_issues_reviews
 *  WHERE report_review_id IN (SELECT id FROM core.report_reviews
 *                              WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%'));
 * DELETE FROM core.report_reviews   WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%');
 * DELETE FROM core.report_holds     WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%');
 * DELETE FROM core.adjuster_reviews WHERE report_id IN (SELECT id FROM core.reports WHERE case_no LIKE 'K6-%');
 * DELETE FROM core.reports          WHERE case_no LIKE 'K6-%';
 * DELETE FROM core.user_claims      WHERE user_id IN (SELECT id FROM core.users WHERE nickname LIKE 'k6-%');
 * -- 계정(users)은 재사용 가능하므로 마지막에 판단해서 지운다.
 * </pre>
 * 부하테스트 중 k6가 {@code POST /reports}를 호출했다면 그 리포트의 case_no는 {@code yyyyMMdd-NNN}
 * 형식이라 위 조건에 걸리지 않는다 — 그 시나리오를 넣었다면 정리 조건에 {@code user_id IN (k6 유저)}를
 * 함께 쓴다.
 */
@Slf4j
@Component
@Profile("!test & !prod")
@Order(20)
public class K6ScenarioSeedRunner implements ApplicationRunner {

  /** {@code K6AdjusterSeedRunner}가 만드는 사정사 수. 이만큼 없으면 시딩을 시작하지 않는다. */
  private static final int ADJUSTER_COUNT = 10;

  private static final String ADJUSTER_NICKNAME_PREFIX = "k6-adjuster-";

  private static final String USER_NICKNAME_PREFIX = "k6-user-";

  /** 합성 생년월일·성별({@code users.birth_date}·{@code gender}는 NOT NULL). {@code DevLoginService} 기본값과 동일. */
  private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 1, 1);

  private static final String GENDER = "MALE";

  private static final String REGION_MAJOR = "서울";

  private static final String REGION_MINOR = "경기";

  /** 5명 중 1명은 "경기"로 둔다 — 사정사 활동지역이 전부 "서울"이라 region 필터가 100% 히트가 되지 않게. */
  private static final int REGION_MINOR_EVERY = 5;

  /** 진행 로그 간격(유저 수). */
  private static final int PROGRESS_LOG_EVERY = 10;

  /** M 풀 방에 미리 넣는 메시지 수(SYSTEM 1 + TEXT 40) — 요약 로그 집계용. */
  private static final int DURABLE_ROOM_MESSAGE_COUNT = 41;

  private final boolean enabled;
  private final int userCount;
  private final int proposalReportsPerUser;
  private final int consultRoomsPerUser;
  private final int durableRoomsPerUser;
  private final int inspectionReportsPerUser;
  private final UserRepository userRepository;
  private final JdbcTemplate jdbcTemplate;
  private final K6ScenarioSeedWriter writer;

  public K6ScenarioSeedRunner(
      @Value("${app.dev-seed.k6-scenarios-enabled:false}") boolean enabled,
      @Value("${app.dev-seed.k6-user-count:100}") int userCount,
      @Value("${app.dev-seed.k6-proposal-reports-per-user:8}") int proposalReportsPerUser,
      @Value("${app.dev-seed.k6-consult-rooms-per-user:36}") int consultRoomsPerUser,
      @Value("${app.dev-seed.k6-durable-rooms-per-user:2}") int durableRoomsPerUser,
      @Value("${app.dev-seed.k6-inspection-reports-per-user:5}") int inspectionReportsPerUser,
      UserRepository userRepository,
      JdbcTemplate jdbcTemplate,
      K6ScenarioSeedWriter writer) {
    this.enabled = enabled;
    this.userCount = userCount;
    this.proposalReportsPerUser = proposalReportsPerUser;
    this.consultRoomsPerUser = consultRoomsPerUser;
    this.durableRoomsPerUser = durableRoomsPerUser;
    this.inspectionReportsPerUser = inspectionReportsPerUser;
    this.userRepository = userRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.writer = writer;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }

    List<UUID> adjusterIds;
    try {
      adjusterIds = resolveAdjusterIds();
    } catch (RuntimeException ex) {
      // 닉네임 중복 행(users.nickname은 UNIQUE가 아니다) 등으로 조회 자체가 실패하면 여기서 흡수한다.
      // 흡수하지 않으면 run() 밖으로 예외가 나가 ApplicationRunner 단계에서 앱 기동 자체가 실패한다.
      log.error("k6 시나리오 시딩 중단 — 사정사 계정 조회 실패. k6-adjuster-* 닉네임 중복 여부를 확인할 것.", ex);
      return;
    }
    if (adjusterIds.size() < ADJUSTER_COUNT) {
      // fail-fast. 사정사가 모자란 채로 절반만 만들면 P 풀 제안 수가 유저마다 달라져 k6 소모량 계산이 깨진다.
      log.error("k6 시나리오 시딩 중단 — 사정사 계정이 {}/{}명뿐이다. "
          + "APP_DEV_SEED_K6_ADJUSTERS_ENABLED=true로 사정사 시딩을 먼저 실행할 것.",
          adjusterIds.size(), ADJUSTER_COUNT);
      return;
    }

    long startedAt = System.currentTimeMillis();
    Counters counters = new Counters();
    log.info("k6 시나리오 시딩 시작 — users={}, per-user P/D/M/I={}/{}/{}/{}",
        userCount, proposalReportsPerUser, consultRoomsPerUser, durableRoomsPerUser, inspectionReportsPerUser);

    for (int seq = 1; seq <= userCount; seq++) {
      try {
        seedUser(seq, adjusterIds, counters);
      } catch (RuntimeException ex) {
        // 1명 실패로 나머지가 날아가지 않게 유저 단위로 흡수한다. 남은 풀은 건너뛰므로 idx에 구멍이
        // 생기지 않고, 다음 실행의 (유저,풀) 카운트가 정확히 이어서 채운다.
        counters.failedUsers++;
        log.warn("k6 시나리오 시딩 실패 — userSeq={} (다음 유저로 계속 진행한다)", seq, ex);
      }
      if (seq % PROGRESS_LOG_EVERY == 0) {
        log.info("k6 시나리오 시딩 진행 — {}/{} 유저, reports={}, elapsed={}ms",
            seq, userCount, counters.reports, System.currentTimeMillis() - startedAt);
      }
    }

    log.info("k6 시나리오 시딩 완료 — users created={}, failed={} / reports created={}, skipped={} / "
        + "reviews={}, rooms={}, messages={} / elapsed={}ms",
        counters.users, counters.failedUsers, counters.reports, counters.skippedReports,
        counters.reviews, counters.rooms, counters.messages, System.currentTimeMillis() - startedAt);
  }

  private void seedUser(int seq, List<UUID> adjusterIds, Counters counters) {
    User user = ensureUser(seq, counters);
    UUID userId = user.getId();

    for (int idx : missingIndexes(K6ScenarioSeedWriter.POOL_PROPOSAL, seq, proposalReportsPerUser, counters)) {
      writer.seedProposalReport(userId, seq, idx, adjusterIds);
      counters.reports++;
      counters.reviews += adjusterIds.size();
    }
    for (int idx : missingIndexes(K6ScenarioSeedWriter.POOL_CONSULT, seq, consultRoomsPerUser, counters)) {
      writer.seedConsultRoom(userId, seq, idx, adjusterIds.get(idx % adjusterIds.size()));
      counters.reports++;
      counters.reviews++;
      counters.rooms++;
      counters.messages++;
    }
    for (int idx : missingIndexes(K6ScenarioSeedWriter.POOL_DURABLE, seq, durableRoomsPerUser, counters)) {
      writer.seedDurableRoom(userId, seq, idx, adjusterIds.get(idx % adjusterIds.size()));
      counters.reports++;
      counters.reviews++;
      counters.rooms++;
      counters.messages += DURABLE_ROOM_MESSAGE_COUNT;
    }
    for (int idx : missingIndexes(K6ScenarioSeedWriter.POOL_INSPECTION, seq, inspectionReportsPerUser, counters)) {
      writer.seedInspectionReport(userId, seq, idx);
      counters.reports++;
    }
  }

  /**
   * 계정을 확보한다 — <b>반드시 "조회 → 없을 때만 생성"</b>이다. {@code users.nickname}은 UNIQUE가 아니라
   * (V1의 제약을 V2에서 제거) 중복 방지가 전적으로 애플리케이션 책임이고, 중복 행이 생기면
   * {@code findByNickname}이 {@code IncorrectResultSizeDataAccessException}을 던져 그 닉네임의 dev 로그인이
   * 영구히 깨진다.
   *
   * <p>이미 있는 계정의 {@code region}은 덮어쓰지 않는다. {@code POST /auth/dev/login}이 먼저 만든 계정은
   * region이 {@code null}이라 {@code GET /reports/pending-review?region=서울} 필터에서 그 유저의 리포트가
   * 빠진다 — 지역 필터를 부하 시나리오에 넣는다면 <b>이 시딩을 dev-login보다 먼저</b> 돌려야 한다.
   */
  private User ensureUser(int seq, Counters counters) {
    String nickname = USER_NICKNAME_PREFIX + seq;
    User existing = userRepository.findByNickname(nickname).orElse(null);
    if (existing != null) {
      return existing;
    }
    User created = userRepository.save(User.create(
        nickname, BIRTH_DATE, GENDER, null, null, Role.USER, regionOf(seq)));
    counters.users++;
    return created;
  }

  /**
   * (유저, 풀)에서 아직 만들어지지 않은 idx 목록. 이미 있는 수만큼은 건너뛰고 그 뒤 번호만 잇는다.
   * 카운트 기준이 {@code case_no} 접두어라 유저·풀·순번이 전부 키에 들어 있고, 이전 실행이 중간에 죽어
   * 한 풀만 반쯤 찬 상태도 정확히 이어서 복구된다.
   */
  private List<Integer> missingIndexes(String pool, int seq, int target, Counters counters) {
    int existing = countSeeded(pool, seq);
    counters.skippedReports += Math.min(existing, target);
    List<Integer> indexes = new ArrayList<>();
    for (int idx = existing + 1; idx <= target; idx++) {
      indexes.add(idx);
    }
    return indexes;
  }

  private int countSeeded(String pool, int seq) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM reports WHERE case_no LIKE ?",
        Integer.class, K6ScenarioSeedWriter.caseNoPrefixPattern(pool, seq));
    return count == null ? 0 : count;
  }

  private static List<String> regionOf(int seq) {
    return (seq % REGION_MINOR_EVERY == 0) ? List.of(REGION_MINOR) : List.of(REGION_MAJOR);
  }

  private List<UUID> resolveAdjusterIds() {
    List<UUID> ids = new ArrayList<>();
    for (int seq = 1; seq <= ADJUSTER_COUNT; seq++) {
      userRepository.findByNickname(ADJUSTER_NICKNAME_PREFIX + seq).map(User::getId).ifPresent(ids::add);
    }
    return ids;
  }

  /** 요약 로그용 집계. 시드 값·식별자는 로그에 남기지 않고 수치와 case_no 접두어만 남긴다. */
  private static final class Counters {
    private int users;
    private int reports;
    private int skippedReports;
    private int reviews;
    private int rooms;
    private int messages;
    private int failedUsers;
  }
}
