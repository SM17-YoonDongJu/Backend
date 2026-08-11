package com.soma.backend.domain.user.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.entity.UserInsuranceFixture;
import com.soma.backend.domain.user.repository.UserInsuranceRepository;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * GET /users/me/insurances 회귀 테스트(design.md §12.4 C29).
 *
 * <p>5개 PII 컬럼이 {@code bytea}로 바뀐 뒤에도 목록 응답이 복호화된 평문을 snake_case로 그대로 내리는지,
 * {@code coverages}의 null → {@code []} coalesce 규약({@code UserInsuranceListResponse.Item.from})이
 * 유지되는지 실제 test_db에 저장한 데이터로 확인한다. 슬라이스 테스트({@code UserInsuranceControllerTest})는
 * 서비스가 목이라 컨버터를 지나지 않으므로 이 경로를 대체하지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("내 보험 정보 조회 PII 회귀 테스트")
class UserInsuranceApiPiiRegressionTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserInsuranceRepository userInsuranceRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(UserInsuranceFixture.createdAtDefaultDdl());
    User user = userRepository.save(
        User.create("보험조회유저", LocalDate.of(1990, 1, 1), "", null, Role.USER, null));
    userId = user.getId();

    userInsuranceRepository.save(UserInsuranceFixture.of(
        userId, "OO손해보험", "무배당 행복드림 종합보험", "100-2024-558123", LocalDate.of(2024, 3, 15),
        List.of("상해후유장해", "골절진단비", "입원일당"), "https://cdn.example.com/policy.pdf"));
    // coverages·policy_no·enrolled_at 미입력(null) 항목 — coalesce·null 유지 규약 확인용.
    userInsuranceRepository.save(UserInsuranceFixture.of(
        userId, "△△생명", "든든 의료실비보험", null, null, null, null));

    entityManager.flush();
    entityManager.clear();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("C29 암호화된 5개 컬럼이 목록 응답에서 평문으로 복원되고 coverages 순서도 유지된다")
  void getMyInsurances_returnsDecryptedPii() throws Exception {
    mockMvc.perform(get("/users/me/insurances").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.list", Matchers.hasSize(2)))
        .andExpect(jsonPath("$.data.list[?(@.insurer_name == 'OO손해보험')]", Matchers.hasSize(1)))
        .andExpect(jsonPath(
            "$.data.list[?(@.product_name == '무배당 행복드림 종합보험')].policy_no",
            Matchers.contains("100-2024-558123")))
        .andExpect(jsonPath(
            "$.data.list[?(@.product_name == '무배당 행복드림 종합보험')].enrolled_at",
            Matchers.contains("2024-03-15")))
        .andExpect(jsonPath(
            "$.data.list[?(@.product_name == '무배당 행복드림 종합보험')].coverages[0]",
            Matchers.contains("상해후유장해")))
        .andExpect(jsonPath(
            "$.data.list[?(@.product_name == '무배당 행복드림 종합보험')].coverages[2]",
            Matchers.contains("입원일당")));
  }

  @Test
  @DisplayName("C29 coverages가 null인 항목은 빈 배열로 coalesce되고 나머지 미입력 값은 null로 유지된다")
  void getMyInsurances_nullCoveragesCoalesceToEmptyArray() throws Exception {
    mockMvc.perform(get("/users/me/insurances").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.data.list[?(@.product_name == '든든 의료실비보험')].coverages",
            Matchers.contains(Matchers.empty())))
        .andExpect(jsonPath("$.data.list[?(@.product_name == '든든 의료실비보험')].policy_no",
            Matchers.contains(Matchers.nullValue())))
        .andExpect(jsonPath("$.data.list[?(@.product_name == '든든 의료실비보험')].enrolled_at",
            Matchers.contains(Matchers.nullValue())))
        .andExpect(jsonPath("$.data.list[?(@.product_name == '든든 의료실비보험')].policy_file_url",
            Matchers.contains(Matchers.nullValue())));
  }

  @Test
  @DisplayName("C29 다른 사용자의 보험은 조회되지 않는다(userId 스코프 유지)")
  void getMyInsurances_otherUser_seesNothing() throws Exception {
    UUID otherUserId = userRepository.save(
        User.create("타인", LocalDate.of(1991, 1, 1), "", null, Role.USER, null)).getId();
    CustomUserDetails other = new CustomUserDetails(otherUserId, "USER");

    mockMvc.perform(get("/users/me/insurances").with(authentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list").isEmpty());
  }
}
