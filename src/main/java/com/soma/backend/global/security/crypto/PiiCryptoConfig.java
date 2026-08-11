package com.soma.backend.global.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

import com.soma.backend.infra.kms.EncryptionKeyStore;
import com.soma.backend.infra.kms.KmsEnvelopePiiDataKeyProvider;

/**
 * PII 암복호화 DEK 공급자 배선. {@code app.crypto.pii.kms-key-id} <b>유무</b>로 구현체를 고른다 —
 * {@code SqsConfig}가 {@code aws.sqs.endpoint} 유무로 LocalStack ↔ 실 AWS를 가르는 것과 동일한 관례다.
 *
 * <ul>
 *   <li>비어 있음(로컬·개발·테스트) → {@link RawPiiDataKeyProvider}. KMS·{@code encryption_keys} 미접근</li>
 *   <li>값 있음(운영) → {@link KmsEnvelopePiiDataKeyProvider}. wrapped DEK를 {@code kms:Decrypt}로 푼다</li>
 * </ul>
 *
 * <p>{@code @Profile}이 아니라 프로퍼티로 가르는 이유: 스테이징 리허설처럼 dev 프로파일에서 실제 KMS를
 * 붙여 검증하고 싶은 경우와, prod 프로파일로 로컬 재현을 돌리는 경우를 모두 수용하기 위해서다. "운영에서
 * raw 키 경로로 조용히 떨어지는" 사고만 {@link PiiCryptoProdGuard}가 따로 막는다(design.md §4.1).
 *
 * <p>{@link KmsClient}는 {@code S3Config}·{@code SqsConfig}와 동일하게 정적 키를 주입하지 않고
 * {@link DefaultCredentialsProvider}(IAM Role·{@code ~/.aws})에 위임한다. 필요한 IAM 액션은
 * {@code kms:GenerateDataKey}·{@code kms:Decrypt}이며 CMK 생성·정책 적용은 이 코드의 범위 밖이다.
 */
@Configuration
public class PiiCryptoConfig {

  @Value("${app.crypto.pii.key:}")
  private String base64Key;

  @Value("${app.crypto.pii.kms-key-id:}")
  private String kmsKeyId;

  @Value("${aws.region:ap-northeast-2}")
  private String region;

  /**
   * DEK 공급자. 어느 구현이든 키 해석은 지연(lazy)이라 이 메서드에서는 KMS·DB를 호출하지 않는다 —
   * 이 빈은 EntityManagerFactory 부트스트랩 중 생성되는 컨버터 경로에 물려 있다.
   */
  @Bean
  public PiiDataKeyProvider piiDataKeyProvider(JdbcTemplate jdbcTemplate) {
    if (!StringUtils.hasText(kmsKeyId)) {
      return new RawPiiDataKeyProvider(base64Key);
    }
    KmsClient kmsClient = KmsClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
    return new KmsEnvelopePiiDataKeyProvider(kmsClient, kmsKeyId, new EncryptionKeyStore(jdbcTemplate));
  }
}
