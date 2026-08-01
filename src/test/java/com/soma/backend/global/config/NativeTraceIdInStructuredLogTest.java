package com.soma.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;

/**
 * 트레이싱 활성 시 Boot 구조화 로깅(logstash)이 traceId·spanId를 <b>네이티브로</b> JSON에 넣는지 검증한다
 * (Micrometer가 MDC를 채우고 logstash 포맷이 이를 필드로 출력 — 별도 커스터마이저 불필요).
 *
 * <p>회귀 방지: 과거 {@code StructuredLoggingJsonMembersCustomizer}로 traceId를 중복 주입했다가
 * "name 'traceId' has already been written"으로 in-span 로그가 통째로 유실됐다. 그런 커스터마이저가
 * 다시 등록되면 span 안에서 찍은 이 로그 줄이 append 실패로 사라져 {@code orElseThrow}에서 이 테스트가 깨진다.
 */
@SpringBootTest(
    classes = NativeTraceIdInStructuredLogTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.profiles.active=prod",
      "spring.main.banner-mode=off",
      "management.tracing.enabled=true",
      "management.tracing.sampling.probability=1.0",
      "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318/v1/traces"
    })
@ExtendWith(OutputCaptureExtension.class)
class NativeTraceIdInStructuredLogTest {

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    OpenTelemetrySdkAutoConfiguration.class,
    ObservationAutoConfiguration.class,
    MicrometerTracingAutoConfiguration.class,
    OpenTelemetryTracingAutoConfiguration.class,
    OtlpTracingAutoConfiguration.class
  })
  static class Config {
  }

  private static final Logger log = LoggerFactory.getLogger("corr.test");

  @Autowired
  ObservationRegistry observationRegistry;

  @Test
  void structuredLogIncludesTraceIdAndSpanIdNatively(CapturedOutput output) {
    String[] traceId = new String[1];
    Observation.createNotStarted("t", observationRegistry).observe(() -> {
      traceId[0] = Span.current().getSpanContext().getTraceId();
      log.error("CORR_MARKER_LINE");
    });

    String markerLine = output.getOut().lines()
        .filter(line -> line.contains("CORR_MARKER_LINE"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("in-span 로그가 유실됨 — traceId 중복 주입 충돌 의심"));

    assertThat(markerLine).contains("\"traceId\":\"" + traceId[0] + "\"");
    assertThat(markerLine).contains("\"spanId\":\"");
  }
}
