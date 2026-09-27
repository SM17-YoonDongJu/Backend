package com.soma.backend.domain.adjuster.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.adjuster.repository.AdjusterApplicationRepository;

/**
 * 전문분야 목록이 불변 리스트({@code List.copyOf})가 된 뒤에도 {@code adjuster_applications.specialties}
 * ({@code text[]}) 컬럼에 정상 저장·재조회되는지 test_db에서 검증한다.
 *
 * <p>단위 테스트로는 확인할 수 없는 지점이다 — {@code @JdbcTypeCode(SqlTypes.ARRAY)} 매핑이 불변
 * 컬렉션을 읽어 배열로 바꾸고, 로딩할 때 자기 리스트로 되돌리는 왕복이 실제 드라이버까지 내려가야
 * 드러난다. PR #323에서 "불변 리스트로 감싸면 Hibernate 동작이 달라질 수 있다"고 보류했던 우려를
 * 실제로 확인하기 위한 회귀 테스트다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("전문분야 text[] 영속성")
class AdjusterApplicationSpecialtiesPersistenceTest {

  @Autowired
  private AdjusterApplicationRepository adjusterApplicationRepository;

  @PersistenceContext
  private EntityManager entityManager;

  private AdjusterApplication application(List<String> specialties) {
    return AdjusterApplication.create(
        UUID.randomUUID(),
        new ApplicantProfile("홍길동", "010-1234-5678", 5, "소개"),
        new LicenseProof("제2024-0001호", null),
        new ActivityScope(specialties, "서울 송파", Affiliation.INDEPENDENT),
        "https://x/reg.pdf");
  }

  @Test
  @DisplayName("불변 리스트로 만든 전문분야가 저장 후 그대로 조회된다")
  void immutableSpecialtiesRoundTrip() {
    AdjusterApplication saved =
        adjusterApplicationRepository.saveAndFlush(application(new ArrayList<>(List.of("신체", "재물"))));
    entityManager.clear();

    AdjusterApplication found = adjusterApplicationRepository.findById(saved.getId()).orElseThrow();

    assertThat(found.getSpecialties()).containsExactly("신체", "재물");
  }

  @Test
  @DisplayName("전문분야가 하나여도 배열 컬럼에 정상 저장된다")
  void singleSpecialtyRoundTrip() {
    AdjusterApplication saved = adjusterApplicationRepository.saveAndFlush(application(List.of("신체")));
    entityManager.clear();

    AdjusterApplication found = adjusterApplicationRepository.findById(saved.getId()).orElseThrow();

    assertThat(found.getSpecialties()).containsExactly("신체");
  }
}
