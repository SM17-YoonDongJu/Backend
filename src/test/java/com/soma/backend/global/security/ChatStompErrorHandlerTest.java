package com.soma.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * STOMP 구독 거절 프레임 다운그레이드(설계서 §5-1) 단위 테스트.
 *
 * <p>핵심 단언은 <b>"반환 프레임의 커맨드가 {@link StompCommand#MESSAGE}인가"</b>다. Spring의
 * {@code StompSubProtocolHandler.sendToClient}는 나가는 프레임의 커맨드가 ERROR인지 하나만 보고 세션을
 * 1002로 닫으므로, 이 단언이 곧 "연결이 안 끊긴다"의 증명이다.
 *
 * <p>인가 실패는 {@code AbstractMessageChannel}이 {@link MessageDeliveryException}으로 래핑해서 넘기므로,
 * 여기서도 실제 런타임과 같게 래핑된 예외를 넣는다.
 */
@DisplayName("ChatStompErrorHandler 단위 테스트")
class ChatStompErrorHandlerTest {

  private static final String ERROR_FLAG_HEADER = "x-stomp-error";
  private static final String SUBSCRIPTION_HEADER = "subscription";
  private static final String METRIC_NAME = "chat.ws.stomp.error";
  private static final String SUBSCRIPTION_ID = "sub-1";

  private SimpleMeterRegistry meterRegistry;
  private ChatStompErrorHandler errorHandler;
  private UUID roomId;
  private String destination;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    // 직렬화 결과를 실제로 단언해야 하므로 mock이 아닌 실물 JsonMapper를 쓴다.
    errorHandler = new ChatStompErrorHandler(JsonMapper.builder().build(), meterRegistry);
    roomId = UUID.randomUUID();
    destination = "/topic/chat.rooms." + roomId;
  }

  private Message<byte[]> subscribeMessage(String subscriptionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    if (subscriptionId != null) {
      accessor.setSubscriptionId(subscriptionId);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private Throwable deliveryFailure(Message<byte[]> clientMessage, ErrorCode errorCode) {
    return new MessageDeliveryException(clientMessage, "failure", new BusinessException(errorCode));
  }

  private StompHeaderAccessor accessorOf(Message<byte[]> frame) {
    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(frame, StompHeaderAccessor.class);
    assertThat(accessor).as("반환 프레임에서 StompHeaderAccessor를 꺼낼 수 있어야 한다(setLeaveMutable(true))").isNotNull();
    return accessor;
  }

  private String bodyOf(Message<byte[]> frame) {
    return new String(frame.getPayload(), StandardCharsets.UTF_8);
  }

  private double counterValue(String code, String action) {
    Counter counter = meterRegistry.find(METRIC_NAME).tags("code", code, "action", action).counter();
    return counter == null ? 0d : counter.count();
  }

  @Nested
  @DisplayName("구독 거절 — MESSAGE 프레임으로 다운그레이드(연결 유지)")
  class SubscribeRejection {

    @Test
    @DisplayName("비참여자 구독 거절은 요청 구독으로 배달되는 MESSAGE 프레임이 된다")
    void notAMember_returnsMessageFrame() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));

      assertThat(frame).isNotNull();
      StompHeaderAccessor accessor = accessorOf(frame);
      assertThat(accessor.getCommand()).isEqualTo(StompCommand.MESSAGE);
      assertThat(accessor.getDestination()).isEqualTo(destination);
      assertThat(accessor.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
      assertThat(accessor.getFirstNativeHeader(SUBSCRIPTION_HEADER)).isEqualTo(SUBSCRIPTION_ID);
      assertThat(accessor.getMessage()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER.name());
      assertThat(accessor.getFirstNativeHeader(ERROR_FLAG_HEADER)).isEqualTo("true");
      assertThat(accessor.getMessageId()).isNotBlank();
      // octet-stream이면 BinaryMessage로 나간다 — JSON이어야 TextMessage.
      assertThat(accessor.getContentType()).isNotNull();
      assertThat(accessor.getContentType().toString()).startsWith("application/json");
      assertThat(bodyOf(frame))
          .isEqualTo("{\"status\":\"403\",\"code\":\"CHAT_NOT_A_MEMBER\",\"message\":\"채팅방 멤버가 아닙니다.\"}");
    }

    @Test
    @DisplayName("방이 없으면 status 404 / CHAT_ROOM_NOT_FOUND 본문을 싣는다")
    void roomNotFound_returnsMessageFrameWith404() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_ROOM_NOT_FOUND));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.MESSAGE);
      assertThat(bodyOf(frame)).contains("\"status\":\"404\"", "\"code\":\"CHAT_ROOM_NOT_FOUND\"");
    }

    @Test
    @DisplayName("미인증 구독은 status 401 / CHAT_WS_UNAUTHORIZED 본문을 싣는다")
    void unauthorized_returnsMessageFrameWith401() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_WS_UNAUTHORIZED));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.MESSAGE);
      assertThat(bodyOf(frame)).contains("\"status\":\"401\"", "\"code\":\"CHAT_WS_UNAUTHORIZED\"");
    }

    @Test
    @DisplayName("원인 체인이 중첩돼도 BusinessException을 찾아낸다")
    void nestedCause_stillResolvesBusinessException() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);
      Throwable nested = new RuntimeException(
          "wrapper", deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(clientMessage, nested);

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.MESSAGE);
      assertThat(bodyOf(frame)).contains("\"code\":\"CHAT_NOT_A_MEMBER\"");
    }

    @Test
    @DisplayName("SUBSCRIBE에 id 헤더가 없으면 subscription 헤더 없이 보낸다(NPE 없음)")
    void missingSubscriptionId_omitsSubscriptionHeader() {
      Message<byte[]> clientMessage = subscribeMessage(null);

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));

      assertThat(frame).isNotNull();
      StompHeaderAccessor accessor = accessorOf(frame);
      assertThat(accessor.getCommand()).isEqualTo(StompCommand.MESSAGE);
      assertThat(accessor.getSubscriptionId()).isNull();
      assertThat(accessor.getFirstNativeHeader(SUBSCRIPTION_HEADER)).isNull();
    }

    @Test
    @DisplayName("클라이언트가 receipt를 보냈으면 receipt-id로 상관관계를 돌려준다")
    void clientReceipt_isEchoedAsReceiptId() {
      StompHeaderAccessor clientAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
      clientAccessor.setDestination(destination);
      clientAccessor.setSubscriptionId(SUBSCRIPTION_ID);
      clientAccessor.setReceipt("receipt-7");
      clientAccessor.setLeaveMutable(true);
      Message<byte[]> clientMessage =
          MessageBuilder.createMessage(new byte[0], clientAccessor.getMessageHeaders());

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getReceiptId()).isEqualTo("receipt-7");
    }
  }

  @Nested
  @DisplayName("폴백 — Spring 기본 동작(ERROR 프레임 + 연결 종료) 유지")
  class Fallback {

    @Test
    @DisplayName("프레임 파싱 실패(clientMessage == null)는 ERROR 프레임으로 남긴다")
    void parseFailure_returnsErrorFrame() {
      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          null, new MessageDeliveryException("깨진 프레임"));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.ERROR);
      assertThat(counterValue("PARSE_FAILURE", "error_frame")).isEqualTo(1d);
    }

    @Test
    @DisplayName("BusinessException이 아닌 예외는 ERROR 프레임으로 남긴다")
    void nonBusinessException_returnsErrorFrame() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, new MessageDeliveryException(clientMessage, "boom", new IllegalStateException("boom")));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.ERROR);
      assertThat(counterValue("UNEXPECTED", "error_frame")).isEqualTo(1d);
    }

    @Test
    @DisplayName("CONNECT 실패처럼 SUBSCRIBE가 아닌 커맨드는 ERROR 프레임으로 남긴다(세션 상태가 이미 버려짐)")
    void nonSubscribeCommand_returnsErrorFrame() {
      StompHeaderAccessor clientAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
      clientAccessor.setLeaveMutable(true);
      Message<byte[]> clientMessage =
          MessageBuilder.createMessage(new byte[0], clientAccessor.getMessageHeaders());

      Message<byte[]> frame = errorHandler.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_WS_UNAUTHORIZED));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.ERROR);
      assertThat(counterValue("NON_SUBSCRIBE", "error_frame")).isEqualTo(1d);
    }

    @Test
    @DisplayName("원인 체인이 순환 참조여도 무한루프 없이 ERROR 프레임으로 떨어진다")
    void selfReferencingCause_terminates() {
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame =
          errorHandler.handleClientMessageProcessingError(clientMessage, new SelfCausedException());

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.ERROR);
    }

    @Test
    @DisplayName("MESSAGE 프레임 직렬화에 실패하면 ERROR 프레임으로 폴백한다(FRAME_BUILD_FAILURE)")
    void serializationFailure_fallsBackToErrorFrame() {
      JsonMapper throwingMapper = mock(JsonMapper.class);
      when(throwingMapper.writeValueAsBytes(any())).thenThrow(new RuntimeException("serialization boom"));
      ChatStompErrorHandler handlerWithThrowingMapper = new ChatStompErrorHandler(throwingMapper, meterRegistry);
      Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

      Message<byte[]> frame = handlerWithThrowingMapper.handleClientMessageProcessingError(
          clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));

      assertThat(frame).isNotNull();
      assertThat(accessorOf(frame).getCommand()).isEqualTo(StompCommand.ERROR);
      assertThat(counterValue("FRAME_BUILD_FAILURE", "error_frame")).isEqualTo(1d);
    }
  }

  @Test
  @DisplayName("메트릭은 다운그레이드(message_frame)와 폴백(error_frame)을 코드별로 구분해 센다")
  void metric_splitsByAction() {
    Message<byte[]> clientMessage = subscribeMessage(SUBSCRIPTION_ID);

    errorHandler.handleClientMessageProcessingError(
        clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_NOT_A_MEMBER));
    errorHandler.handleClientMessageProcessingError(
        clientMessage, deliveryFailure(clientMessage, ErrorCode.CHAT_ROOM_NOT_FOUND));
    errorHandler.handleClientMessageProcessingError(null, new MessageDeliveryException("깨진 프레임"));

    assertThat(counterValue("CHAT_NOT_A_MEMBER", "message_frame")).isEqualTo(1d);
    assertThat(counterValue("CHAT_ROOM_NOT_FOUND", "message_frame")).isEqualTo(1d);
    assertThat(counterValue("PARSE_FAILURE", "error_frame")).isEqualTo(1d);
    assertThat(counterValue("CHAT_NOT_A_MEMBER", "error_frame")).isZero();
  }

  /** {@code getCause()}가 자기 자신을 가리키는 병리적 예외 — 원인 체인 탐색의 무한루프 방어를 검증한다. */
  private static final class SelfCausedException extends RuntimeException {

    SelfCausedException() {
      super("self-caused");
    }

    @Override
    public synchronized Throwable getCause() {
      return this;
    }
  }
}
