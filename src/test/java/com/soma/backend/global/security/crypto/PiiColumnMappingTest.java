package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.soma.backend.global.security.crypto.converter.ReportQuestionConverter;
import com.soma.backend.global.security.crypto.converter.UserClaimAdditionalInformationConverter;
import com.soma.backend.global.security.crypto.converter.UserClaimDescriptionConverter;
import com.soma.backend.global.security.crypto.converter.UserClaimQuestionConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceCoveragesConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceEnrolledAtConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceInsurerNameConverter;
import com.soma.backend.global.security.crypto.converter.UserInsurancePolicyNoConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceProductNameConverter;

/**
 * PII 컬럼 매핑·배선의 스코프 경계 고정(design.md §12.4 C32, §12.5-1·2).
 *
 * <p>테스트 프로파일은 {@code ddl-auto: create-drop}이라 스키마가 <b>엔티티 매핑에서</b> 생성된다. 즉
 * {@code information_schema}가 보는 컬럼 타입이 곧 "엔티티가 선언한 타입"이다 — 컨버터를 붙였는데
 * {@code @JdbcTypeCode(SqlTypes.ARRAY)}·{@code columnDefinition}을 지우지 않았다면 여기서 드러난다
 * (운영에서는 {@code ddl-auto: validate}가 기동 실패로 드러날 문제를 테스트 시점으로 당긴다).
 *
 * <p>컨버터가 Spring 빈으로 배선되는지도 함께 확인한다. 배선이 실패하면 Hibernate가 no-arg 생성자로
 * 폴백을 시도하다 깨지거나, 최악의 경우 조용히 다른 경로로 흘러가므로 실기동 검증이 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PII 컬럼 매핑·컨버터 배선 테스트")
class PiiColumnMappingTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PiiCipher piiCipher;

  @Autowired
  private PiiDataKeyProvider piiDataKeyProvider;

  @Autowired
  private UserClaimAdditionalInformationConverter additionalInformationConverter;

  @Autowired
  private UserInsuranceInsurerNameConverter insurerNameConverter;

  @Autowired
  private UserInsuranceProductNameConverter productNameConverter;

  @Autowired
  private UserInsurancePolicyNoConverter policyNoConverter;

  @Autowired
  private UserInsuranceEnrolledAtConverter enrolledAtConverter;

  @Autowired
  private UserInsuranceCoveragesConverter coveragesConverter;

  @Autowired
  private ReportQuestionConverter reportQuestionConverter;

  @Autowired
  private UserClaimDescriptionConverter descriptionConverter;

  @Autowired
  private UserClaimQuestionConverter claimQuestionConverter;

  private String dataTypeOf(String table, String column) {
    return jdbcTemplate.queryForObject(
        "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        String.class, table, column);
  }

  @ParameterizedTest(name = "{0}.{1} → bytea")
  @CsvSource({
    "user_claims, additional_information",
    "user_insurances, insurer_name",
    "user_insurances, product_name",
    "user_insurances, policy_no",
    "user_insurances, enrolled_at",
    "user_insurances, coverages"
  })
  @DisplayName("PR-1 대상 6개 컬럼은 엔티티 매핑상 bytea다")
  void pr1Columns_areMappedAsBytea(String table, String column) {
    assertThat(dataTypeOf(table, column)).isEqualTo("bytea");
  }

  @ParameterizedTest(name = "{0}.{1} → bytea")
  @CsvSource({
    "reports, question",
    "user_claims, description",
    "user_claims, question"
  })
  @DisplayName("이슈 #228 대상 3개 컬럼은 엔티티 매핑상 bytea다")
  void issue228Columns_areMappedAsBytea(String table, String column) {
    assertThat(dataTypeOf(table, column)).isEqualTo("bytea");
  }

  /**
   * 스코프 밖 컬럼이 실수로 함께 암호화되지 않았는지 고정한다. 기대 타입은 <b>엔티티 매핑이 만드는 타입</b>
   * 기준이다 — {@code columnDefinition}이 없는 {@code String} 필드는 Hibernate가
   * {@code varchar(255)}(= {@code character varying})로 생성하므로, 운영 Flyway 스키마의 {@code text}와
   * 이름이 다르다. 어느 쪽이든 {@code bytea}가 아니라는 점이 이 테스트의 관심사다.
   */
  @ParameterizedTest(name = "{0}.{1} → {2} 유지")
  @CsvSource({
    "reports, applicable_guarantees, ARRAY",
    "reports, omitted_special_contract, ARRAY",
    "reports, basis_terms_precedents, ARRAY",
    "reports, documents, jsonb",
    "reports, treatment, character varying",
    "report_reviews, applicable_guarantees, ARRAY",
    "user_claims, details, jsonb"
  })
  @DisplayName("스코프 밖(PR-2 게이트·jsonb fix 대기·검수본) 컬럼은 타입이 그대로다")
  void outOfScopeColumns_keepOriginalType(String table, String column, String expectedType) {
    assertThat(dataTypeOf(table, column)).isEqualTo(expectedType).isNotEqualTo("bytea");
  }

  @Test
  @DisplayName("§12.5-1 컨버터 6종이 모두 Spring 빈으로 배선되고 PiiCipher가 주입돼 실제로 왕복한다")
  void converters_areWiredAsSpringBeans() {
    assertThat(piiCipher).isNotNull();
    assertThat(piiDataKeyProvider).isInstanceOf(RawPiiDataKeyProvider.class);

    assertThat(additionalInformationConverter.convertToEntityAttribute(
        additionalInformationConverter.convertToDatabaseColumn("부가설명"))).isEqualTo("부가설명");
    assertThat(insurerNameConverter.convertToEntityAttribute(
        insurerNameConverter.convertToDatabaseColumn("OO손해보험"))).isEqualTo("OO손해보험");
    assertThat(productNameConverter.convertToEntityAttribute(
        productNameConverter.convertToDatabaseColumn("행복드림"))).isEqualTo("행복드림");
    assertThat(policyNoConverter.convertToEntityAttribute(
        policyNoConverter.convertToDatabaseColumn("100-1"))).isEqualTo("100-1");
    assertThat(enrolledAtConverter.convertToDatabaseColumn(null)).isNull();
    assertThat(coveragesConverter.convertToDatabaseColumn(null)).isNull();
    assertThat(reportQuestionConverter.convertToEntityAttribute(
        reportQuestionConverter.convertToDatabaseColumn("문의합니다"))).isEqualTo("문의합니다");
    assertThat(descriptionConverter.convertToEntityAttribute(
        descriptionConverter.convertToDatabaseColumn("사고 경위"))).isEqualTo("사고 경위");
    assertThat(claimQuestionConverter.convertToEntityAttribute(
        claimQuestionConverter.convertToDatabaseColumn("질문 있습니다"))).isEqualTo("질문 있습니다");
  }
}
