package com.soma.backend.infra.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * OCR 트리거 Kafka producer 설정({@link OutboxRelay}가 사용). consumer(OCR 소비/실행)는 FastAPI
 * 담당이라 이 앱은 producer만 구성한다. bootstrap-servers/security.protocol은 기존
 * application.yml(spring.kafka.*) 값을 그대로 읽는다 — 로컬 KRaft ↔ 운영 MSK 전환 시에도
 * 이 클래스는 손대지 않고 환경변수만 바꾸면 된다.
 */
@Configuration
public class KafkaProducerConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${spring.kafka.security.protocol:PLAINTEXT}")
  private String securityProtocol;

  @Bean
  public ProducerFactory<String, String> ocrProducerFactory() {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    // 유실 방지 — 모든 ISR이 기록을 확인한 뒤에만 성공 처리.
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    // 중복 방지 — 브로커 재시도 시에도 동일 메시지가 한 번만 커밋된다(정확히 한 번 전달의 전제 조건).
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.RETRIES_CONFIG, 3);
    // idempotence=true에서 순서 보장이 유효한 상한값(Kafka 권장 ≤5, 브로커가 프로듀서당 유지하는 배치 수).
    config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
    config.put(ProducerConfig.LINGER_MS_CONFIG, 10);
    return new DefaultKafkaProducerFactory<>(config);
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> ocrProducerFactory) {
    return new KafkaTemplate<>(ocrProducerFactory);
  }
}
