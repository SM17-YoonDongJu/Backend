---
name: spring-infra
description: "Spring Boot 인프라·관측성·배포 하드닝 구현 가이드. Actuator 헬스체크·liveness/readiness 프로브, JVM 메모리 제한(MaxRAMPercentage)·GC 로그·OOM 힙덤프, HikariCP 커넥션 풀 제한, Kafka producer 안전설정(acks·idempotence)과 로컬 KRaft 브로커 docker compose, Dockerfile·docker-compose 하드닝(restart·healthcheck·mem_limit), PII-안전 로깅(logback), curl·k6 smoke test 작성 시 반드시 이 스킬을 참조. '헬스체크 열기', 'JVM 메모리 제한', 'GC 로그', 'DB 풀 제한', 'Kafka producer 설정', 'docker healthcheck', '프로덕션 하드닝', '관측성 세팅', 'smoke test' 요청과 이들의 '다시/수정/보완/추가' 후속 요청 시 사용. 비즈니스 로직 구현은 제외(springboot-dev 사용)."
---

# Spring Boot 인프라·관측성·배포 하드닝 가이드

infra-developer 에이전트가 참조하는 운영 준비(production readiness) 구현 가이드. 비즈니스 로직이 아니라 앱이 안정적으로 뜨고·관측되고·배포되는 **설정과 배선**을 다룬다.

이 프로젝트의 제약을 지킨다: `open-in-view: false`, `ddl-auto: validate`, Flyway, snake_case, UUID PK, 응답 포맷(`ApiResponse`). 설정 값에는 **왜 그 값인지** 근거 주석을 남긴다.

## 대상 파일
| 영역 | 파일 |
|------|------|
| 앱 설정(풀·producer·actuator·로깅) | `src/main/resources/application.yml` |
| 로깅 상세 | `src/main/resources/logback-spring.xml` |
| JVM·컨테이너 | `Dockerfile` |
| 서비스 오케스트레이션 | `docker-compose.yml` |
| 의존성 | `build.gradle` |
| 환경 문서 | `.env.example` |
| 정책 문서 | `docs/logging-pii-policy.md` *(생성 예정 — 아직 부재)* |
| 검증 | `scripts/smoke-test.sh`, `scripts/smoke-test.k6.js` |

## 1. Actuator 헬스체크 / 프로브

의존성: `implementation 'org.springframework.boot:spring-boot-starter-actuator'`

**현재 `application.yml` 상태(하드닝 미완):**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # metrics·prometheus까지 노출 — 하드닝 TODO
  endpoint:
    health:
      show-details: always    # 상세 무조건 노출 — 하드닝 TODO(PII·인프라 정보). 권장: when-authorized 또는 never
      probes:
        enabled: true          # liveness/readiness 프로브 → /actuator/health/{liveness,readiness}
```

- **⚠ 하드닝 TODO(민감 노출):** 현재는 `metrics,prometheus`가 열려 있고 `show-details: always`다. `metrics`/`prometheus`는 인증 뒤로 두거나(아래 SecurityConfig 참고) 스크레이프 전용 경로로 좁히고, `show-details`는 외부 노출 환경에선 `never`/`when-authorized`로 조인다. `env`·`configprops`·`heapdump`는 `include`에 없어 여전히 차단됨(넓히지 말 것).
- **SecurityConfig 확인:** 실제 `SecurityConfig`는 `.anyRequest().authenticated()`이고 permitAll은 `/actuator/health`·`/actuator/health/**`만이다(`/auth/**`·`/ws/**`·`/ws-chat/**` 포함). 즉 **`/actuator/info,metrics,prometheus`는 인증이 필요**하다 — 대시보드·Prometheus 스크레이퍼에서 붙이려면 별도 인가(예: 내부망 IP·ADMIN 롤·전용 자격증명)를 security-developer와 배선한다.
- **readiness 그룹:** 기본은 `readinessState`만 포함 → DB 다운이 readiness를 죽이지 않는다. DB 장애 시 트래픽 차단(무중단 배포)을 원하면 `management.endpoint.health.group.readiness.include: readinessState,db` 추가.

## 2. JVM 메모리 제한 / GC 로그 (Dockerfile)

컨테이너에서는 고정 `-Xmx`가 아니라 **`MaxRAMPercentage`**로 컨테이너 메모리 한계를 인식하게 한다. `JAVA_OPTS`로 오버라이드 가능하게 배선한다.

```dockerfile
RUN mkdir -p /app/logs
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 \
-XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof \
-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

- **`exec`**를 쓰는 이유: java가 PID 1이 되어 SIGTERM(graceful shutdown)을 직접 받는다. `sh -c "java ..."` 없이 exec 없으면 시그널이 java에 전달 안 됨.
- **compose `mem_limit`와 정합:** `mem_limit: 1g` × `MaxRAMPercentage=75%` ≈ 힙 768m. mem_limit를 바꾸면 힙이 자동으로 따라간다.
- **GC 로그**는 회전(`filecount`/`filesize`)으로 디스크 폭주 방지. 힙덤프는 OOM 시 1회 저장 → 사후 분석용.
- **`application.yml`**: `server.shutdown: graceful` 추가로 진행 중 요청 처리 후 종료.

## 3. HikariCP 커넥션 풀 제한 (application.yml)

**현재 `application.yml` 상태(적용된 것만):**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000   # 커넥션 대기 30초 초과 시 예외
```

**하드닝 TODO(아직 미적용 — 권장 추가값):**
```yaml
      pool-name: ${DB_POOL_NAME:soma-hikari}          # 로그·모니터링 식별
      maximum-pool-size: ${DB_POOL_MAX_SIZE:10}       # env 오버라이드
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      idle-timeout: 600000        # 유휴 10분 후 회수
      max-lifetime: 1740000       # 최대 수명 29분 — DB/LB 타임아웃보다 짧게
      keepalive-time: 300000
```

- **풀 크기 근거:** 무한정 늘리면 DB가 죽는다. `maximum-pool-size`는 DB `max_connections`와 인스턴스 수를 역산해서 정한다(기본 10은 단일 인스턴스 보수값). 하드닝 시 env로 환경별 오버라이드를 배선한다.
- **`max-lifetime`은 DB/LB 유휴 종료 시간보다 짧게** — 그래야 죽은 커넥션을 쥐고 있다가 터지는 걸 막는다. 현재는 미설정(Hikari 기본 30분)이라 명시 설정을 권장한다.

## 4. Kafka producer 안전설정 + 로컬 브로커

의존성: `implementation 'org.springframework.kafka:spring-kafka'`. **consumer는 FastAPI 담당이라 producer만 설정.**

> **실제 배선:** 이 프로젝트는 producer 안전설정을 yaml이 아니라 **Java `@Configuration KafkaProducerConfig`
> (`infra/kafka/KafkaProducerConfig.java`)** 로 구현한다 — `ocrProducerFactory`(ProducerFactory) + `KafkaTemplate`
> 빈을 노출하고, `OutboxRelay`가 이 템플릿으로 OCR 트리거를 발행한다. `bootstrap-servers`/`security.protocol`만
> `application.yml`의 `spring.kafka.*`에서 읽는다(로컬 KRaft ↔ 운영 MSK 전환 시 이 클래스는 불변, 환경변수만 교체).
> 아래 값은 그 빈이 `ProducerConfig`로 설정하는 값과 동일하다(등가 yaml 참고용).

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    security:
      protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}
    producer:                     # 참고: 실제로는 KafkaProducerConfig(ProducerConfig)로 동일 값 배선
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all                   # 모든 ISR 확인 후 성공 — 유실 방지
      retries: 3
      properties:
        enable.idempotence: true  # 중복/재전송 방지 (정확히 한 번 전송)
        max.in.flight.requests.per.connection: 5
        delivery.timeout.ms: 120000
        request.timeout.ms: 30000
        linger.ms: 10
```

로컬 브로커는 **KRaft 모드(zookeeper 불필요)**로 compose에 올리고, 호스트/컨테이너 이중 리스너로 붙게 한다. 운영은 MSK로 오버라이드(추후).

```yaml
  kafka:
    image: apache/kafka:4.3.1
    ports:
      - "${KAFKA_PORT:-9092}:9092"      # EXTERNAL → 호스트 bootRun
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      # 호스트 생략(://:포트) 표기 필수 — 리터럴 0.0.0.0은 컨트롤러 광고 주소 폴백 검증에 걸려 기동 실패(#93)
      KAFKA_LISTENERS: CONTROLLER://:9093,INTERNAL://:9094,EXTERNAL://:9092
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9094,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s
```

- **이중 리스너 이유:** 컨테이너 내부는 `kafka:9094`(INTERNAL), 호스트 bootRun은 `localhost:9092`(EXTERNAL). advertised listener가 한 개면 한쪽이 못 붙는다.
- app 서비스에 `KAFKA_BOOTSTRAP_SERVERS: kafka:9094`(env_file보다 우선) + `depends_on: kafka(healthy)` 추가.
- 단일 브로커 → 복제 계수 1. 운영(MSK)에서는 3으로.
- **producer 안전설정은 브로커 위치와 무관하게 유효** — 로컬↔MSK 전환 시 이 값은 안 바꾼다.

## 5. PII-안전 로깅

application.yml에서 SQL 바인드·본문 로깅을 억제하고, `logback-spring.xml`로 이중 안전장치를 건다. `spring.jpa.show-sql`은 **쓰지 않는다**(바인드 값 노출).

```yaml
logging:
  level:
    org.hibernate.SQL: warn
    org.hibernate.orm.jdbc.bind: warn        # 바인드 파라미터(=실제 값) 억제
    org.hibernate.type.descriptor.sql: warn
    org.springframework.web.filter.CommonsRequestLoggingFilter: warn
```

`logback-spring.xml`은 body/header를 포함하지 않는 표준 패턴 + 회전 파일 appender로 구성한다. 손해사정 도메인은 주민번호·진단서·결제정보를 다루므로 정책 문서(`docs/logging-pii-policy.md`, **아직 미생성 — 생성 예정**)를 함께 유지한다: 금지 항목(토큰·주민번호·의료·금융·요청 본문), 마스킹 규칙, DTO 통째 로깅 금지.

## 6. Docker restart / healthcheck (docker-compose app)

```yaml
    restart: unless-stopped
    mem_limit: 1g            # JAVA_OPTS MaxRAMPercentage=75% 기준
    volumes:
      - ./logs:/app/logs     # GC 로그·힙덤프 호스트 보존
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 40s      # 부팅 대기 — 부팅 중 unhealthy 오탐 방지
```

- **`restart: unless-stopped`** — 크래시 시 자동 복구하되 사용자가 명시적으로 멈추면 유지. `on-failure`는 수동 재시작을 못 살린다.
- healthcheck는 **readiness** 프로브를 본다(liveness는 살아만 있으면 UP). alpine JRE의 busybox `wget` 사용.
- `.gitignore`에 `logs/`, `*.hprof`, `*.log` 추가.

## 7. Smoke test (배포 후 검증)

`scripts/smoke-test.sh`(curl)는 health/liveness/readiness 상태코드 + 본문 `UP`을 확인한다. `scripts/smoke-test.k6.js`(k6)는 최소 부하로 실패율·p95 threshold를 검증한다. 엔드포인트가 늘면 스크립트에 추가한다. 스모크=배포 후 헬스 확인, 기능 검증은 통합 테스트(spring-qa) 몫 — 경계를 지킨다.

## 검증 절차 (구현 후)
1. `./gradlew compileJava` — 의존성/컴파일 확인
2. `DB_PASSWORD=x docker compose config >/dev/null` — compose 문법 검증(엔진 불필요)
3. Docker 가동 시: `docker compose up -d` → `docker compose --profile app up -d` → `BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh`
4. Docker 미가동 시 라이브 스모크는 건너뛰고 summary에 명시

## summary.md 출력 형식
`_workspace/02_infra/summary.md`에 기록:
- 변경 파일 목록
- 각 설정 값과 **근거**(왜 이 값인지)
- 검증 결과(컴파일/compose config 통과 여부, 라이브 스모크 실행/미실행)
- 남은 TODO (예: 실제 KafkaProducer 컴포넌트 구현은 backend-developer)
