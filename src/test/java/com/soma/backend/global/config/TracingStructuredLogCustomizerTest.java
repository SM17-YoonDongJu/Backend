package com.soma.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

class TracingStructuredLogCustomizerTest {

  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String SPAN_ID = "b7ad6b7169203331";

  @Test
  void extractsTraceAndSpanIdWhileSpanIsCurrent() {
    SpanContext context = SpanContext.create(
        TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
    try (Scope scope = Span.wrap(context).makeCurrent()) {
      assertThat(TracingStructuredLogCustomizer.currentTraceId()).isEqualTo(TRACE_ID);
      assertThat(TracingStructuredLogCustomizer.currentSpanId()).isEqualTo(SPAN_ID);
    }
  }

  @Test
  void returnsNullWhenNoSpanIsCurrent() {
    assertThat(TracingStructuredLogCustomizer.currentTraceId()).isNull();
    assertThat(TracingStructuredLogCustomizer.currentSpanId()).isNull();
  }
}
