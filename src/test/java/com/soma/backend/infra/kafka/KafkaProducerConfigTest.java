package com.soma.backend.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * KafkaProducerConfig의 환경별 IAM 인증 분기 검증(단위 — 스프링 컨텍스트·브로커 불필요).
 * 로컬/기본(PLAINTEXT)엔 SASL 설정이 붙지 않고, 운영 MSK(SASL_SSL)일 때만 AWS_MSK_IAM이 배선되는지 고정한다.
 */
class KafkaProducerConfigTest {

  @SuppressWarnings("unchecked")
  private Map<String, Object> producerConfigWith(String securityProtocol) {
    KafkaProducerConfig sut = new KafkaProducerConfig();
    ReflectionTestUtils.setField(sut, "bootstrapServers", "localhost:9092");
    ReflectionTestUtils.setField(sut, "securityProtocol", securityProtocol);
    DefaultKafkaProducerFactory<String, String> factory =
        (DefaultKafkaProducerFactory<String, String>) sut.ocrProducerFactory();
    return factory.getConfigurationProperties();
  }

  @Test
  void plaintext_omitsIamSaslConfig() {
    Map<String, Object> props = producerConfigWith("PLAINTEXT");
    assertThat(props).doesNotContainKey(SaslConfigs.SASL_MECHANISM);
    assertThat(props).doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
  }

  @Test
  void saslSsl_addsMskIamConfig() {
    Map<String, Object> props = producerConfigWith("SASL_SSL");
    assertThat(props).containsEntry(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
    assertThat(props).containsEntry(SaslConfigs.SASL_JAAS_CONFIG,
        "software.amazon.msk.auth.iam.IAMLoginModule required;");
    assertThat(props).containsEntry(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
        "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
  }
}
