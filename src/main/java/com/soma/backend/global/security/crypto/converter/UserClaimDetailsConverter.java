package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_claims.details}(사고 유형별 청구 상세, 다형성 값 객체) 컬럼 암복호화 — {@code jsonb}가 아니라
 * JSON 문자열로 직렬화한 뒤 통째로 1회 암호화해 {@code bytea}에 담는다({@code user_insurances.coverages}와
 * 동일 패턴, design.md §6). AAD = {@code user_claims:details}.
 *
 * <p>{@link ClaimDetails}는 {@code @JsonTypeInfo}로 다형성을 식별하므로 역직렬화도 인터페이스 타입
 * ({@code ClaimDetails.class})으로 받으면 Jackson이 알아서 구현체를 고른다.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserClaim} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserClaimDetailsConverter implements AttributeConverter<ClaimDetails, byte[]> {

  private static final PiiAad AAD = PiiAad.ofColumn("user_claims", "details");

  private final PiiCipher cipher;
  private final JsonMapper jsonMapper;

  public UserClaimDetailsConverter(PiiCipher cipher, JsonMapper jsonMapper) {
    // Spring 빈이 아니라 Hibernate no-arg 폴백으로 생성되면 조용한 NPE 대신 여기서 명확히 실패한다(§12.2 C18).
    if (cipher == null || jsonMapper == null) {
      throw new IllegalStateException("PiiCipher/JsonMapper 주입 실패 — 컨버터가 Spring 빈으로 생성되지 않았습니다");
    }
    this.cipher = cipher;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public byte[] convertToDatabaseColumn(ClaimDetails attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return cipher.encryptText(jsonMapper.writeValueAsString(attribute), AAD);
    } catch (JacksonException ex) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }

  @Override
  public ClaimDetails convertToEntityAttribute(byte[] dbData) {
    if (dbData == null) {
      return null;
    }
    String json = cipher.decryptText(dbData, AAD);
    try {
      return jsonMapper.readValue(json, ClaimDetails.class);
    } catch (JacksonException ex) {
      // 복호문(json)을 예외 메시지에 실으면 GlobalExceptionHandler의 catch-all 로그로 평문이 샌다 — 정규화해서 차단.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }
}
