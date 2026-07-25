package com.soma.backend.global.config;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * 구조화 JSON 로그(logstash)에 현재 span의 {@code traceId}·{@code spanId}를 주입한다 —
 * Grafana에서 로그↔트레이스 상관(derivedField)이 걸리게 하기 위함.
 *
 * <p>왜 필요한가: Boot의 구조화 로깅 포맷터는 MDC를 읽는데, Boot의 트레이스 correlation은
 * 평문 pattern 전용({@code %correlationId} 컨버터가 트레이스 컨텍스트를 직접 읽음)이라 MDC를 채우지 않는다.
 * 그래서 구조화 로그엔 traceId가 실리지 않는다. 이 커스터마이저는 로그 기록 시점에 스레드로컬 OTel 컨텍스트의
 * {@link Span#current()}에서 직접 값을 뽑아 넣는다(요청 밖 로그는 유효 span이 없어 필드가 생략됨).
 *
 * <p>로깅은 애플리케이션 컨텍스트보다 먼저 초기화되므로 Spring 빈이 될 수 없다 —
 * {@code logging.structured.json.customizer} 프로퍼티(application-{dev,prod}.yml)로 등록한다.
 */
public class TracingStructuredLogCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

  @Override
  public void customize(JsonWriter.Members<ILoggingEvent> members) {
    members.add("traceId", () -> currentTraceId()).whenNotNull();
    members.add("spanId", () -> currentSpanId()).whenNotNull();
  }

  static String currentTraceId() {
    SpanContext context = Span.current().getSpanContext();
    return context.isValid() ? context.getTraceId() : null;
  }

  static String currentSpanId() {
    SpanContext context = Span.current().getSpanContext();
    return context.isValid() ? context.getSpanId() : null;
  }
}
