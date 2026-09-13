package com.soma.backend.global.security;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.ErrorResponse;

/**
 * STOMP 인바운드 처리 실패를 클라이언트에 전달하는 에러 핸들러. WS 인가 실패({@link ChatSubscribeInterceptor})의
 * STOMP 판 anti-corruption 지점으로, REST의 {@code GlobalExceptionHandler}·{@link RestAccessDeniedHandler}와
 * 같은 역할을 한다.
 *
 * <p><b>왜 ERROR 프레임을 돌려주지 않는가.</b> STOMP 1.2는 서버가 ERROR 프레임을 보낸 뒤 연결을 닫도록
 * 규정하고, Spring의 {@code StompSubProtocolHandler.sendToClient}는 <b>나가는 프레임의 커맨드가 ERROR인지</b>
 * 하나만 보고 세션을 {@code CloseStatus.PROTOCOL_ERROR}(1002)로 닫는다. 에러 핸들러를 등록했는지는 이 판단에
 * 아무 영향이 없다. 그래서 기본 구현처럼 ERROR 프레임을 만들어 돌려주면 구독 하나가 거절될 때 그 세션의
 * <b>정상 구독까지 전부 끊긴다.</b> 이를 피하려고 구독 거절만 <b>MESSAGE 커맨드 프레임으로 다운그레이드</b>해
 * 같은 소켓으로 돌려준다(연결 유지). 일반 채팅 메시지와 구분되도록 {@code x-stomp-error:true} 헤더와
 * {@code message:&lt;ERROR_CODE&gt;} 헤더를 함께 싣는다.
 *
 * <p><b>회귀 방지:</b> 이 핸들러를 {@code WebSocketConfig.registerStompEndpoints}의
 * {@code registry.setErrorHandler(...)}에서 빼면 곧바로 위 1002 종료 동작으로 되돌아간다. Spring Boot는
 * {@code StompSubProtocolErrorHandler} 빈을 자동 탐지하지 않으므로 {@code @Component} 등록만으로는 효과가 없다.
 *
 * <p>동작을 바꾸는 범위는 <b>SUBSCRIBE + {@link BusinessException}</b>이라는 좁은 allowlist뿐이다. 프레임 파싱
 * 실패·CONNECT 실패·예상 못 한 예외는 Spring 기본 동작(ERROR 프레임 + 연결 종료)에 그대로 위임한다 — 특히
 * CONNECT 실패는 Spring이 이미 세션 상태를 버려서 살려두면 이후 모든 프레임이 실패한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStompErrorHandler extends StompSubProtocolErrorHandler {

  /** 에러 프레임임을 클라이언트가 명시적으로 판별하기 위한 커스텀 STOMP 헤더. */
  private static final String ERROR_FLAG_HEADER = "x-stomp-error";
  private static final String METRIC_NAME = "chat.ws.stomp.error";
  private static final String CODE_TAG = "code";
  private static final String ACTION_TAG = "action";
  private static final String MESSAGE_FRAME_ACTION = "message_frame";
  private static final String ERROR_FRAME_ACTION = "error_frame";
  private static final int MAX_CAUSE_DEPTH = 10;

  private final JsonMapper jsonMapper;
  private final MeterRegistry meterRegistry;

  @Override
  public @Nullable Message<byte[]> handleClientMessageProcessingError(
      @Nullable Message<byte[]> clientMessage, Throwable ex) {
    if (clientMessage == null) {
      // 프레임 자체를 파싱하지 못한 진짜 프로토콜 오류 — 종료가 옳다.
      return delegateToSuper(null, ex, "PARSE_FAILURE");
    }
    StompHeaderAccessor clientAccessor = MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
    if (clientAccessor == null || !StompCommand.SUBSCRIBE.equals(clientAccessor.getCommand())) {
      // CONNECT 실패 등 — Spring이 이미 세션 상태를 버렸으므로 기본 동작(종료)을 유지한다.
      return delegateToSuper(clientMessage, ex, "NON_SUBSCRIBE");
    }
    // ex는 BusinessException이 아니라 AbstractMessageChannel이 씌운 MessageDeliveryException이다.
    BusinessException businessEx = findBusinessException(ex);
    if (businessEx == null) {
      log.warn("STOMP SUBSCRIBE 처리 중 예상하지 못한 예외 — ERROR 프레임으로 종료한다", ex);
      return delegateToSuper(clientMessage, ex, "UNEXPECTED");
    }

    ErrorCode errorCode = businessEx.getErrorCode();
    Message<byte[]> frame;
    try {
      frame = subscribeRejectionFrame(clientAccessor, errorCode);
    } catch (RuntimeException frameEx) {
      // 프레임을 못 만들면 최소한 기본 ERROR 프레임은 나가게 폴백한다(핸들러 밖으로 예외를 흘리면 아무것도 못 간다).
      log.warn("STOMP 구독 거절 프레임 생성 실패 — ERROR 프레임으로 폴백한다", frameEx);
      return delegateToSuper(clientMessage, ex, "FRAME_BUILD_FAILURE");
    }
    recordResult(errorCode.name(), MESSAGE_FRAME_ACTION);
    return frame;
  }

  /** Spring 기본 동작(ERROR 프레임 → 연결 종료)에 위임한다. */
  private @Nullable Message<byte[]> delegateToSuper(
      @Nullable Message<byte[]> clientMessage, Throwable ex, String reason) {
    recordResult(reason, ERROR_FRAME_ACTION);
    return super.handleClientMessageProcessingError(clientMessage, ex);
  }

  /** 원인 체인(최대 {@code MAX_CAUSE_DEPTH})에서 BusinessException을 찾는다. 없으면 null. */
  private static @Nullable BusinessException findBusinessException(Throwable ex) {
    Throwable current = ex;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (current instanceof BusinessException businessEx) {
        return businessEx;
      }
      Throwable cause = current.getCause();
      if (cause == current) {
        // getCause()가 자기 자신을 가리키는 순환 참조 방어.
        return null;
      }
      current = cause;
    }
    return null;
  }

  /** 거절된 SUBSCRIBE에 대응하는 MESSAGE 커맨드 프레임을 만든다(연결 유지용 다운그레이드). */
  private Message<byte[]> subscribeRejectionFrame(StompHeaderAccessor clientAccessor, ErrorCode errorCode) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
    // handleError가 반환 메시지에서 accessor를 다시 꺼내 sendToClient에 넘긴다 — mutable이 아니면 못 꺼낸다.
    accessor.setLeaveMutable(true);
    accessor.setDestination(clientAccessor.getDestination());
    String subscriptionId = clientAccessor.getSubscriptionId();
    if (subscriptionId != null) {
      // 커맨드가 MESSAGE라 네이티브 subscription 헤더로 기록된다 → 거절이 그 구독 콜백으로 정확히 배달된다.
      accessor.setSubscriptionId(subscriptionId);
    }
    accessor.setMessageId(UUID.randomUUID().toString());
    // octet-stream이면 sendToClient가 BinaryMessage로 보낸다 — JSON이어야 TextMessage로 나간다.
    accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
    // 헤더에는 이스케이프·인코딩이 안전한 에러 코드만 싣는다. 사람이 읽을 한글 문구는 body에 있다.
    accessor.setMessage(errorCode.name());
    accessor.setNativeHeader(ERROR_FLAG_HEADER, "true");
    String receipt = clientAccessor.getReceipt();
    if (receipt != null) {
      // 상관관계(correlation)용일 뿐 RECEIPT 프레임이 아니다 — 구독 성공을 뜻하지 않는다.
      accessor.setReceiptId(receipt);
    }
    byte[] payload = jsonMapper.writeValueAsBytes(ErrorResponse.of(errorCode));
    return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
  }

  private void recordResult(String code, String action) {
    meterRegistry.counter(METRIC_NAME, CODE_TAG, code, ACTION_TAG, action).increment();
  }
}
