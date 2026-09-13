package com.soma.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 실제 핸드셰이크 + STOMP 클라이언트로 "구독이 거절돼도 세션과 다른 구독이 살아남는다"를 검증하는 통합 테스트
 * (설계서 §6-2). 단위 테스트가 프레임 커맨드만 보는 것과 달리, 여기서는 <b>거절 후에도 같은 연결의 정상 구독으로
 * 브로드캐스트가 실제로 도착하는지</b>를 본다 — 수정을 되돌리면 소켓이 닫혀 이 단언이 타임아웃으로 실패한다.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다.</b> 서버의 {@link ChatSubscribeInterceptor}는 WebSocket 수신
 * 스레드의 별도 커넥션에서 방을 조회하므로, 테스트 트랜잭션에 갇힌 픽스처는 보이지 않아 참여자 구독까지
 * {@code CHAT_ROOM_NOT_FOUND}로 떨어진다. 커밋해서 저장하고 {@code @AfterEach}에서 직접 지운다.
 *
 * <p>실행 전제: 로컬 {@code docker compose up -d}(PostgreSQL {@code test_db} + Redis).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // RANDOM_PORT는 server.port=0으로 띄우는데 management.server.port는 8080 고정이라 별도 관리 컨텍스트가
    // 8080을 잡는다(다른 로컬 앱과 충돌). 비워서 메인 포트와 같은 컨텍스트를 쓰게 한다.
    properties = "management.server.port=")
@ActiveProfiles("test")
@DisplayName("STOMP 구독 거절 시 세션·다른 구독 생존 통합 테스트")
class ChatStompSubscribeRejectionIntegrationTest {

  private static final String ROOM_TOPIC_PREFIX = "/topic/chat.rooms.";
  private static final String ERROR_FLAG_HEADER = "x-stomp-error";
  private static final String MESSAGE_HEADER = "message";
  private static final long AWAIT_SECONDS = 5;
  /** 구독 등록은 인바운드 채널 executor에서 비동기로 끝나므로, 브로드캐스트는 도착할 때까지 재시도한다. */
  private static final int BROADCAST_ATTEMPTS = 10;
  private static final long BROADCAST_POLL_MILLIS = 500;

  @LocalServerPort
  private int port;

  @Autowired
  private JwtProvider jwtProvider;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ChatRoomRepository chatRoomRepository;
  @Autowired
  private SimpMessagingTemplate messagingTemplate;

  private final List<Throwable> transportErrors = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> sessionExceptions = Collections.synchronizedList(new ArrayList<>());
  private final BlockingQueue<ReceivedFrame> unmatchedFrames = new LinkedBlockingQueue<>();

  private ThreadPoolTaskScheduler taskScheduler;
  private WebSocketStompClient stompClient;
  private StompSession session;

  private UUID memberId;
  private UUID myRoomId;
  private UUID othersRoomId;

  @BeforeEach
  void setUp() {
    taskScheduler = new ThreadPoolTaskScheduler();
    taskScheduler.setPoolSize(1);
    taskScheduler.setThreadNamePrefix("stomp-test-");
    taskScheduler.afterPropertiesSet();

    stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    // 거절 프레임은 content-type이 application/json이라 StringMessageConverter(text/plain 전용)로는 변환에
    // 실패한다. 지원 MIME을 제한하지 않는 기본 SimpleMessageConverter로 원문 바이트를 그대로 받는다.
    stompClient.setMessageConverter(new SimpleMessageConverter());
    stompClient.setTaskScheduler(taskScheduler);

    User member = userRepository.save(
        User.create("채팅고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    User adjuster = userRepository.save(
        User.create("담당사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    User stranger = userRepository.save(
        User.create("제3자고객", LocalDate.of(1992, 3, 3), "F", null, null, Role.USER, null));
    User otherAdjuster = userRepository.save(
        User.create("제3자사정사", LocalDate.of(1983, 5, 5), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    memberId = member.getId();

    myRoomId = chatRoomRepository.save(
        ChatRoomFixture.build(memberId, adjuster.getId(), null, null, ChatRoomStatus.ACTIVE)).getId();
    othersRoomId = chatRoomRepository.save(
        ChatRoomFixture.build(stranger.getId(), otherAdjuster.getId(), null, null, ChatRoomStatus.ACTIVE)).getId();
  }

  @AfterEach
  void tearDown() {
    if (session != null && session.isConnected()) {
      session.disconnect();
    }
    stompClient.stop();
    taskScheduler.shutdown();
    // @Transactional 롤백이 없으므로 커밋된 픽스처를 직접 정리한다.
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();
  }

  private StompSession connectAsMember() throws Exception {
    String token = jwtProvider.generateAccessToken(memberId, Role.USER.name());
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, CookieProvider.ACCESS_TOKEN_COOKIE + "=" + token);
    // Origin은 보내지 않는다 — OriginHandshakeInterceptor는 origin이 null이면 통과시킨다(k6와 같은 조건).
    return stompClient
        .connectAsync("ws://localhost:" + port + "/ws-chat", handshakeHeaders, new RecordingSessionHandler())
        .get(AWAIT_SECONDS, TimeUnit.SECONDS);
  }

  private BlockingQueue<ReceivedFrame> subscribe(UUID roomId) {
    BlockingQueue<ReceivedFrame> frames = new LinkedBlockingQueue<>();
    session.subscribe(ROOM_TOPIC_PREFIX + roomId, new RecordingFrameHandler(frames));
    return frames;
  }

  private ReceivedFrame broadcastUntilReceived(UUID roomId, String payload, BlockingQueue<ReceivedFrame> frames)
      throws InterruptedException {
    for (int attempt = 0; attempt < BROADCAST_ATTEMPTS; attempt++) {
      messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + roomId, payload);
      ReceivedFrame frame = frames.poll(BROADCAST_POLL_MILLIS, TimeUnit.MILLISECONDS);
      if (frame != null) {
        return frame;
      }
    }
    return null;
  }

  private void assertRejection(ReceivedFrame frame, UUID roomId, ErrorCode errorCode) {
    assertThat(frame).as("거절 프레임이 해당 구독으로 배달돼야 한다").isNotNull();
    assertThat(frame.headers().getFirst(ERROR_FLAG_HEADER)).isEqualTo("true");
    assertThat(frame.headers().getFirst(MESSAGE_HEADER)).isEqualTo(errorCode.name());
    assertThat(frame.headers().getDestination()).isEqualTo(ROOM_TOPIC_PREFIX + roomId);
    assertThat(frame.body()).contains("\"code\":\"" + errorCode.name() + "\"");
  }

  private void assertSessionHealthy() {
    assertThat(session.isConnected()).as("구독 거절이 연결을 끊으면 안 된다").isTrue();
    assertThat(transportErrors).as("전송 오류(세션 종료 포함)가 없어야 한다").isEmpty();
    assertThat(sessionExceptions).as("클라이언트 프레임 처리 예외가 없어야 한다").isEmpty();
    assertThat(unmatchedFrames).as("ERROR 프레임 등 구독에 매칭되지 않은 프레임이 없어야 한다").isEmpty();
  }

  @Test
  @DisplayName("access_token 쿠키로 핸드셰이크가 성공한다(StandardWebSocketClient Cookie 전달 실측)")
  void handshake_withAccessTokenCookie_connects() throws Exception {
    session = connectAsMember();

    assertThat(session.isConnected()).isTrue();
    assertThat(transportErrors).isEmpty();
  }

  @Test
  @DisplayName("없는 방 구독이 거절돼도 연결과 기존 정상 구독이 살아있다")
  void rejectedSubscription_keepsSessionAndOtherSubscriptionAlive() throws Exception {
    session = connectAsMember();
    BlockingQueue<ReceivedFrame> goodFrames = subscribe(myRoomId);
    UUID missingRoomId = UUID.randomUUID();

    BlockingQueue<ReceivedFrame> rejectFrames = subscribe(missingRoomId);

    assertRejection(
        rejectFrames.poll(AWAIT_SECONDS, TimeUnit.SECONDS), missingRoomId, ErrorCode.CHAT_ROOM_NOT_FOUND);
    assertSessionHealthy();
    // 핵심 단언 — isConnected() 플래그가 아니라 실제 메시지가 돌아오는 것이 "다른 구독이 무사하다"의 증거다.
    ReceivedFrame delivered = broadcastUntilReceived(myRoomId, "{\"content\":\"살아있다\"}", goodFrames);
    assertThat(delivered).as("거절 이후에도 정상 구독으로 브로드캐스트가 도착해야 한다").isNotNull();
    assertThat(delivered.body()).contains("살아있다");
  }

  @Test
  @DisplayName("비참여자 방 구독은 CHAT_NOT_A_MEMBER로 거절되고 연결은 유지된다")
  void notAMemberSubscription_isRejectedWithoutClosingSession() throws Exception {
    session = connectAsMember();
    BlockingQueue<ReceivedFrame> goodFrames = subscribe(myRoomId);

    BlockingQueue<ReceivedFrame> rejectFrames = subscribe(othersRoomId);

    assertRejection(
        rejectFrames.poll(AWAIT_SECONDS, TimeUnit.SECONDS), othersRoomId, ErrorCode.CHAT_NOT_A_MEMBER);
    assertSessionHealthy();
    assertThat(broadcastUntilReceived(myRoomId, "{\"content\":\"여전히 수신\"}", goodFrames)).isNotNull();
  }

  @Test
  @DisplayName("첫 구독이 거절돼도 이어지는 정상 구독은 정상 동작한다")
  void rejectionBeforeFirstSuccessfulSubscription_doesNotBreakSession() throws Exception {
    session = connectAsMember();
    UUID missingRoomId = UUID.randomUUID();

    BlockingQueue<ReceivedFrame> rejectFrames = subscribe(missingRoomId);
    assertRejection(
        rejectFrames.poll(AWAIT_SECONDS, TimeUnit.SECONDS), missingRoomId, ErrorCode.CHAT_ROOM_NOT_FOUND);

    BlockingQueue<ReceivedFrame> goodFrames = subscribe(myRoomId);

    assertSessionHealthy();
    assertThat(broadcastUntilReceived(myRoomId, "{\"content\":\"거절 후 구독\"}", goodFrames)).isNotNull();
  }

  private static String asText(Object payload) {
    if (payload instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return payload == null ? "" : payload.toString();
  }

  /** 수신 프레임의 STOMP 헤더와 본문 원문. */
  private record ReceivedFrame(StompHeaders headers, String body) {
  }

  /** 구독으로 배달된 프레임을 큐에 쌓는다. 거절 프레임(JSON)도 원문 바이트로 받도록 payload 타입을 고정한다. */
  private static final class RecordingFrameHandler implements StompFrameHandler {

    private final BlockingQueue<ReceivedFrame> frames;

    private RecordingFrameHandler(BlockingQueue<ReceivedFrame> frames) {
      this.frames = frames;
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return byte[].class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      frames.add(new ReceivedFrame(headers, asText(payload)));
    }
  }

  /** 세션 수준 신호(전송 오류·처리 예외·구독에 매칭되지 않은 ERROR 프레임)를 모아 생존 단언에 쓴다. */
  private final class RecordingSessionHandler extends StompSessionHandlerAdapter {

    @Override
    public void handleTransportError(StompSession stompSession, Throwable ex) {
      transportErrors.add(ex);
    }

    @Override
    public void handleException(
        StompSession stompSession, StompCommand command, StompHeaders headers, byte[] payload, Throwable ex) {
      sessionExceptions.add(ex);
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      unmatchedFrames.add(new ReceivedFrame(headers, asText(payload)));
    }
  }
}
