package com.soma.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * 분산 트레이싱의 OTLP span exporter가 실제로 배선되는지 검증한다(회귀 방지).
 *
 * <p>이 배선은 세 조건이 모두 맞아야 성립하며, 어긋나면 조용히 깨져 Tempo 수신이 0이 된다:
 * <ul>
 *   <li>의존성 {@code micrometer-tracing-bridge-otel} — 없으면 OpenTelemetryPropagationConfigurations가
 *       {@code NoClassDefFoundError(Slf4JBaggageEventListener)}로 컨텍스트 기동 자체가 실패한다.</li>
 *   <li>의존성 {@code opentelemetry-exporter-otlp} — 없으면 OtlpTracingAutoConfiguration이
 *       {@code @ConditionalOnClass(OtlpHttpSpanExporter)}로 backoff한다.</li>
 *   <li>프로퍼티 {@code management.opentelemetry.tracing.export.otlp.endpoint}(Boot 4.0 신규명) —
 *       구 {@code management.otlp.tracing.endpoint}는 deprecated라 런타임 매핑이 안 되고, 이 값이 없으면
 *       OtlpTracingConnectionDetails 빈이 안 생겨 SpanExporter가 {@code @ConditionalOnBean}으로 backoff한다.</li>
 * </ul>
 */
class TracingOtlpExporterWiringTest {

  @Test
  void otlpSpanExporterIsWired() {
    tracingContext()
        .withPropertyValues(
            "management.tracing.export.enabled=true",
            "management.opentelemetry.tracing.export.otlp.endpoint=http://tempo:4318/v1/traces")
        .run(context -> assertThat(context).hasSingleBean(SpanExporter.class));
  }

  /**
   * 전송을 끄는 키는 Boot 4의 {@code management.tracing.export.enabled}다. 구 {@code management.tracing.enabled}는
   * deprecated라 런타임에 무시돼서, 그 키로 끄려던 prod가 수집기 없는 localhost:4318로 span을 보내며 ERROR를 남겼다.
   */
  @Test
  void otlpSpanExporterIsNotWiredWhenExportDisabled() {
    tracingContext()
        .withPropertyValues(
            "management.tracing.export.enabled=false",
            "management.opentelemetry.tracing.export.otlp.endpoint=http://tempo:4318/v1/traces")
        .run(context -> assertThat(context).doesNotHaveBean(SpanExporter.class));
  }

  /** 실제 application.yml·application-prod.yml을 읽었을 때 prod는 span을 내보내지 않는다(수집기 없음). */
  @Test
  void prodProfileDoesNotExportSpans() {
    tracingContext()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> assertThat(context).doesNotHaveBean(SpanExporter.class));
  }

  private ApplicationContextRunner tracingContext() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            OpenTelemetrySdkAutoConfiguration.class,
            MicrometerTracingAutoConfiguration.class,
            OpenTelemetryTracingAutoConfiguration.class,
            OtlpTracingAutoConfiguration.class));
  }
}
