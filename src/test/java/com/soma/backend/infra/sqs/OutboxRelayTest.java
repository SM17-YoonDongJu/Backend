package com.soma.backend.infra.sqs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

  @InjectMocks
  private OutboxRelay outboxRelay;

  private OcrOutboxEvent pendingEvent() {
    return OcrOutboxEvent.pending(
        "OCR_JOB", UUID.randomUUID(), "ocr-job-queue", "job-1", "{\"job_id\":\"job-1\"}");
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
  }

  @Test
  void relay_doesNothing_whenDisabled() {
    ReflectionTestUtils.setField(outboxRelay, "outboxEnabled", false);

    outboxRelay.relay();

    verifyNoInteractions(outboxRepository, sqsClient);
  }
}
