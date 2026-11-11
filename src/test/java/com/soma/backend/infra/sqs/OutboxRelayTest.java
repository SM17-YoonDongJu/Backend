package com.soma.backend.infra.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import com.soma.backend.infra.outbox.OcrOutboxEvent;
import com.soma.backend.infra.outbox.OcrOutboxRepository;
import com.soma.backend.infra.outbox.OcrOutboxStatus;

/** OutboxRelay 검증 — SQS 발행 성공 시 SENT, 실패 시 attempts 증가, 비활성 시 no-op. */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

  @Mock
  private OcrOutboxRepository outboxRepository;

  @Mock
  private SqsClient sqsClient;

  // 카운터 증가를 실제로 기록해야 검증할 수 있어 mock이 아니라 실제 레지스트리를 주입한다.
  @Spy
  private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks
  private OutboxRelay outboxRelay;

  private OcrOutboxEvent pendingEvent() {
    OcrOutboxEvent event = OcrOutboxEvent.pending(
        "OCR_JOB", UUID.randomUUID(), "ocr-job-queue", "job-1", "{\"job_id\":\"job-1\"}");
    // createdAt은 @CreationTimestamp라 DB 저장 시점에 채워진다 — 단위 테스트에서 직접 만든 객체는
    // 비어 있으므로 발행 지연(#306) 계측이 실제로 도는지 보려면 여기서 심어줘야 한다.
    ReflectionTestUtils.setField(event, "createdAt", LocalDateTime.now().minusSeconds(3));
    return event;
  }

  @Test
  void registersBacklogGauges_forPendingAndFailed() {
    given(outboxRepository.countByStatus(OcrOutboxStatus.PENDING)).willReturn(7L);
    given(outboxRepository.countByStatus(OcrOutboxStatus.FAILED)).willReturn(2L);

    // 게이지 값은 등록 시점이 아니라 조회(=스크레이프) 시점에 평가된다.
    assertThat(meterRegistry.get("outbox.events").tag("status", "PENDING").gauge().value()).isEqualTo(7.0);
    assertThat(meterRegistry.get("outbox.events").tag("status", "FAILED").gauge().value()).isEqualTo(2.0);
  }

  @Test
  void relay_marksSent_andSendsToQueue_whenPublishSucceeds() {
    ReflectionTestUtils.setField(outboxRelay, "outboxEnabled", true);
    OcrOutboxEvent event = pendingEvent();
    given(outboxRepository.findBatchForRelay(anyInt())).willReturn(List.of(event));
    given(sqsClient.getQueueUrl(any(Consumer.class)))
        .willReturn(GetQueueUrlResponse.builder().queueUrl("http://localstack:4566/q/ocr-job-queue").build());
    given(sqsClient.sendMessage(any(Consumer.class))).willReturn(SendMessageResponse.builder().build());

    outboxRelay.relay();

    assertThat(event.getStatus()).isEqualTo(OcrOutboxStatus.SENT);
    verify(sqsClient).sendMessage(any(Consumer.class));
    assertThat(meterRegistry.counter("outbox.relay.sent", "queue", "ocr-job-queue").count()).isEqualTo(1.0);
    // 발행 성공 경로가 catch로 새지 않았는지 — attempts가 늘었다면 계측 코드가 예외를 던지고
    // markFailed로 흘러간 것이다(status는 이미 SENT라 상태만 봐서는 안 드러난다).
    assertThat(event.getAttempts()).isZero();
    assertThat(meterRegistry.timer("outbox.relay.latency", "queue", "ocr-job-queue").count()).isEqualTo(1L);
  }

  @Test
  void relay_incrementsAttempts_andKeepsPending_whenPublishFails() {
    ReflectionTestUtils.setField(outboxRelay, "outboxEnabled", true);
    OcrOutboxEvent event = pendingEvent();
    given(outboxRepository.findBatchForRelay(anyInt())).willReturn(List.of(event));
    given(sqsClient.getQueueUrl(any(Consumer.class)))
        .willThrow(SqsException.builder().message("boom").build());

    outboxRelay.relay();

    assertThat(event.getStatus()).isEqualTo(OcrOutboxStatus.PENDING);
    assertThat(event.getAttempts()).isEqualTo(1);
    assertThat(meterRegistry.counter("outbox.relay.failed", "queue", "ocr-job-queue").count()).isEqualTo(1.0);
  }

  @Test
  void relay_doesNothing_whenDisabled() {
    ReflectionTestUtils.setField(outboxRelay, "outboxEnabled", false);

    outboxRelay.relay();

    verifyNoInteractions(outboxRepository, sqsClient);
  }
}
