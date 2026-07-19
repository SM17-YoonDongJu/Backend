package com.soma.backend.infra.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3Client Bean 구성.
 * 자격증명은 {@link DefaultCredentialsProvider}로 자동 탐색한다
 * (EC2/ECS는 IAM Role, 로컬은 ~/.aws/credentials 또는 환경변수).
 * 정적 키를 직접 주입하지 않는다.
 */
@Configuration
public class S3Config {

  @Value("${aws.region:ap-northeast-2}")
  private String region;

  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  /**
   * 채팅 첨부(private 객체)의 단기 다운로드 URL 발급용 presigner. S3Client와 동일하게
   * region·DefaultCredentialsProvider로 구성한다(정적 키 미주입).
   */
  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }
}
