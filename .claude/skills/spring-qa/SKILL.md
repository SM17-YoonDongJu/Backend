---
name: spring-qa
description: "Spring Boot 테스트 작성 가이드. JUnit5·Mockito 단위 테스트, @SpringBootTest 통합·리포지토리·QueryDSL 테스트(외부 test_db), MockMvc API 테스트, Spring Security 테스트. 테스트 작성·추가·수정 요청 시 반드시 이 스킬을 참조."
---

# Spring QA — 테스트 작성 가이드

이 프로젝트의 Spring Boot 테스트 전략과 패턴을 정의한다.

## 테스트 계층 구조

| 계층 | 어노테이션 | 범위 | 속도 |
|------|----------|------|------|
| 단위 테스트 | 없음 (순수 JUnit5) | Service·Domain 로직 | 빠름 |
| 슬라이스 테스트 | @WebMvcTest (Controller) | Controller 단독 | 중간 |
| 통합 테스트 | @SpringBootTest | 전체 컨텍스트·Repository·QueryDSL | 느림 |

> Spring Boot 4의 이 프로젝트 클래스패스에는 `@DataJpaTest` 슬라이스가 없다. Repository·QueryDSL 조회 테스트는 `@SpringBootTest` + `@ActiveProfiles("test")`로 실제 `test_db`에 실행한다(아래 참조).

## 단위 테스트 패턴 (Service 계층)

```java
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @InjectMocks
    private ReportService reportService;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ChatRoomService chatRoomService;

    @Test
    @DisplayName("사정사 배정 시 리포트 상태가 COUNSELING으로 변경된다")
    void assignAdjuster_ShouldUpdateStatusToCounseling() {
        // Given
        UUID reportId = UUID.randomUUID();
        UUID adjusterId = UUID.randomUUID();
        Report report = createReportFixture(ReportStatus.AWAITING_ADOPTION);
        given(reportRepository.findById(reportId)).willReturn(Optional.of(report));

        // When
        reportService.assignAdjuster(reportId, adjusterId);

        // Then
        assertThat(report.getAdjusterId()).isEqualTo(adjusterId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.COUNSELING);
        then(chatRoomService).should().createRoom(report.getUserId(), adjusterId);
    }
}
```

## Repository·QueryDSL 통합 테스트

`@DataJpaTest` 슬라이스는 이 프로젝트(Spring Boot 4) 클래스패스에 없다. Repository·QueryDSL 조회는 `@SpringBootTest` + `@ActiveProfiles("test")`로 실제 `test_db`에 실행한다. QueryDSL 쿼리는 데이터가 없어도 SQL이 준비·실행되므로, 엔티티 조인·서브쿼리·컨버터 projection의 번역 오류를 여기서 잡는다.

```java
@SpringBootTest
@ActiveProfiles("test")
class ReportQuerydslExecutionTest {

    @Autowired
    private ReportRepository reportRepository;

    @Test
    @DisplayName("동적 조회 QueryDSL SQL이 실제 test_db에서 실행된다")
    void findPendingReviewRows_executes() {
        Page<PendingReviewRow> page = reportRepository.findPendingReviewRows(
            null, null, null, UUID.randomUUID(), PageRequest.of(0, 20));
        assertThat(page.getContent()).isEmpty();
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
    void assignAdjuster_WithValidRequest_Returns200() throws Exception { // USER만 사정사 배정 가능
        // Given
        AdjusterAssignRequestDto request = new AdjusterAssignRequestDto(adjusterId);

        // When & Then — 컨트롤러 경로에 /api 프리픽스 없음, 응답은 ApiResponse로 래핑
        mockMvc.perform(post("/reports/{reportId}/adjuster", reportId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("200"))          // ApiResponse.status(문자열)
            .andExpect(jsonPath("$.data.status").value("COUNSELING")); // 도메인 값은 data 아래(snake_case)
    }

    @Test
    @WithMockUser(roles = "CERTIFICATED_ADJUSTER")
    void assignAdjuster_AsAdjuster_Returns403() throws Exception {
        // 사정사 역할의 직접 배정 시도 → 403 검증
    }

    @Test
    @WithMockUser(roles = "UNCERTIFICATED_ADJUSTER")
    void assignAdjuster_AsUncertificatedAdjuster_Returns403() throws Exception {
        // 미인증 사정사 배정 시도 → 403 검증
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

## 테스트 DB 설정

이 프로젝트는 TestContainers를 쓰지 않는다(의존성 미포함). 테스트는 `application-test.yml`이 가리키는 **외부 PostgreSQL `test_db`**(localhost:5432, user/pw `test`)에 붙고, `ddl-auto: create-drop`으로 스키마를 만들며 Flyway는 끈다. Redis·SQS(LocalStack)도 로컬 인스턴스(docker compose)를 그대로 쓴다. 로컬에서 처음 돌릴 때 `test_db`/`test` 롤을 한 번 만들어 둔다:

```sql
CREATE ROLE test LOGIN PASSWORD 'test' CREATEDB;
CREATE DATABASE test_db OWNER test;
```

격리된 일회성 컨테이너 DB가 필요해지면 그때 TestContainers 의존성(`org.testcontainers:postgresql` 등)을 추가하고 `@Testcontainers` + `@DynamicPropertySource`로 배선한다(현재 미도입).

## 테스트 우선순위

| 우선순위 | 대상 | 이유 |
|---------|------|------|
| 필수 | 리포트 사정사 배정 및 상태 변경 | 핵심 비즈니스, 버그 비용 높음 |
| 필수 | 결제 웹훅 멱등성 | 중복 결제 방어 |
| 필수 | JWT 발급·검증·갱신 | 보안 핵심 |
| 필수 | RBAC 권한 거부 케이스 | 403이 올바르게 반환되는지 |
| 권장 | WebSocket 연결 JWT 검증 | 미인증 연결 차단 확인 |

## 테스트 작성 원칙
- Given-When-Then 패턴 준수, @DisplayName은 "~하면 ~된다" 형식
- Repository·QueryDSL 통합 테스트는 @SpringBootTest + @ActiveProfiles("test")로 실제 test_db에 실행한다 (@DataJpaTest 슬라이스는 이 프로젝트에 없음)
- 픽스처는 별도 `*Fixture` 클래스로 분리
- 경계 케이스(null, 빈 값, 권한 없는 역할)는 반드시 포함
