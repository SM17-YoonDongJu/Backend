---
name: spring-qa
description: "Spring Boot 테스트 작성 가이드. JUnit5·Mockito 단위 테스트, @SpringBootTest 통합 테스트, MockMvc API 테스트, TestContainers(PostgreSQL·Redis·Kafka), Spring Security 테스트. 테스트 작성·추가·수정 요청 시 반드시 이 스킬을 참조."
---

# Spring QA — 테스트 작성 가이드

이 프로젝트의 Spring Boot 테스트 전략과 패턴을 정의한다.

## 테스트 계층 구조

| 계층 | 어노테이션 | 범위 | 속도 |
|------|----------|------|------|
| 단위 테스트 | 없음 (순수 JUnit5) | Service·Domain 로직 | 빠름 |
| 슬라이스 테스트 | @DataJpaTest, @WebMvcTest | Repository·Controller 단독 | 중간 |
| 통합 테스트 | @SpringBootTest | 전체 컨텍스트 | 느림 |

## 단위 테스트 패턴 (Service 계층)

```java
@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @InjectMocks
    private MatchingService matchingService;

    @Mock
    private MatchingRepository matchingRepository;

    @Mock
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("매칭 수락 시 채팅 채널이 개설된다")
    void acceptMatching_ShouldCreateChatRoom() {
        // Given
        UUID matchingId = UUID.randomUUID();
        Matching matching = createMatchingFixture(MatchingStatus.PENDING);
        given(matchingRepository.findById(matchingId)).willReturn(Optional.of(matching));

        // When
        matchingService.accept(matchingId);

        // Then
        assertThat(matching.getStatus()).isEqualTo(MatchingStatus.ACCEPTED);
        then(chatRoomService).should().createRoom(matchingId, matching.getUserId(), matching.getAdjusterId());
    }
}
```

## Repository 슬라이스 테스트

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MatchingRepositoryTest {

    @Autowired
    private MatchingRepository matchingRepository;

    @Test
    void findPendingByAdjusterId_ShouldReturnOnlyPendingMatchings() {
        // TestContainers PostgreSQL 사용 (application-test.yml에서 설정)
    }
}
```

## API 통합 테스트 (MockMvc)

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "USER")
    void requestMatching_WithValidRequest_Returns201() throws Exception { // USER만 매칭 요청 가능
        // Given
        MatchingRequestDto request = new MatchingRequestDto(adjusterId, description);

        // When & Then
        mockMvc.perform(post("/api/matching")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.matchingId").exists());
    }

    @Test
    @WithMockUser(roles = "CERTIFICATED_ADJUSTER")
    void requestMatching_AsAdjuster_Returns403() throws Exception {
        // 사정사 역할의 접근 거부 검증
    }

    @Test
    @WithMockUser(roles = "UNCERTIFICATED_ADJUSTER")
    void adoptCase_AsUncertificatedAdjuster_Returns403() throws Exception {
        // 미인증 사정사 케이스 채택 시도 → 403 검증
    }
}
```

## Spring Security 테스트

```java
// 커스텀 UserDetails가 있는 경우
@WithUserDetails(value = "user@test.com", userDetailsServiceBeanName = "customUserDetailsService")

// 특정 권한 직접 지정 — ERD role Enum 그대로 사용
@WithMockUser(username = "adjuster", roles = {"CERTIFICATED_ADJUSTER"})

// JWT 토큰 헤더 직접 설정
mockMvc.perform(get("/api/adjuster/matching")
    .header("Authorization", "Bearer " + testJwtToken))
```

## TestContainers 설정

```java
@SpringBootTest
@Testcontainers
class IntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
```

## 테스트 우선순위

| 우선순위 | 대상 | 이유 |
|---------|------|------|
| 필수 | 매칭 플로우 상태 전이 | 핵심 비즈니스, 버그 비용 높음 |
| 필수 | 결제 웹훅 멱등성 | 중복 결제 방어 |
| 필수 | JWT 발급·검증·갱신 | 보안 핵심 |
| 필수 | RBAC 권한 거부 케이스 | 403이 올바르게 반환되는지 |
| 권장 | Kafka Producer 발행 검증 | 메시지 스키마 회귀 방어 |
| 권장 | WebSocket 연결 JWT 검증 | 미인증 연결 차단 확인 |

## 테스트 작성 원칙
- Given-When-Then 패턴 준수, @DisplayName은 "~하면 ~된다" 형식
- @DataJpaTest는 PostgreSQL TestContainers 사용 (`Replace.NONE`)
- 픽스처는 별도 `*Fixture` 클래스로 분리
- 경계 케이스(null, 빈 값, 권한 없는 역할)는 반드시 포함
