package com.soma.backend.infra.sqs;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * SqsClient Bean 구성. OCR 트리거를 아웃박스 릴레이({@link OutboxRelay})가 SQS로 발행할 때 사용한다.
 * {@code S3Config}와 동일하게 spring-cloud-aws를 쓰지 않고 AWS SDK v2를 직접 배선한다(정적 키 미주입).
 *
 * <p>자격증명: {@code aws.sqs.endpoint}가 비어 있으면(운영/스테이징) {@link DefaultCredentialsProvider}로
 * IAM Role을 자동 위임한다. 값이 있으면(로컬 LocalStack) 실제 AWS 자격증명 없이도 접속하도록 더미
 * StaticCredentials + endpointOverride로 로컬 엔드포인트에 붙는다.
 *
 * <p>{@code apiCallTimeout}을 걸어 릴레이 폴러 스레드가 SQS 응답을 무한정 기다리지 않도록 한다 —
 * {@code FOR UPDATE SKIP LOCKED}로 잡은 행의 트랜잭션 점유 시간을 짧게 묶기 위함이다(OutboxRelay tx timeout과 정합).
 */
@Configuration
public class SqsConfig {

  // 브로커 응답 대기 상한 — BATCH_SIZE(5) × 5s = 25s로, OutboxRelay의 tx timeout 30s 안쪽에 들도록 맞춘다.
  private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

  @Value("${aws.region:ap-northeast-2}")
  private String region;

  // 로컬 LocalStack 엔드포인트(예: http://localhost:4566). 비어 있으면 실제 AWS SQS로 접속한다.
  @Value("${aws.sqs.endpoint:}")
  private String endpoint;

  @Bean
  public SqsClient sqsClient() {
    SqsClientBuilder builder = SqsClient.builder()
        .region(Region.of(region))
        .overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallTimeout(API_CALL_TIMEOUT)
            .build());

    if (StringUtils.hasText(endpoint)) {
      builder
          .endpointOverride(URI.create(endpoint))
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }
    return builder.build();
  }
}
