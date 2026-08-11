package com.soma.backend.global.security.crypto.converter;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * PII 문자열 배열 컬럼(`text[]` → `bytea`)의 AES-256-GCM 봉투 암복호화 베이스(design.md §6·§7.1).
 *
 * <p>배열은 원소별로 암호화하지 않는다 — JSON 배열 문자열로 직렬화한 뒤 통째로 1회 암호화해 단일
 * {@code bytea}에 담는다. 원소별 암호화는 배열 길이(=적용 보장·누락 특약 개수)가 그대로 새어나가고
 * 부분 갱신 시 nonce 재사용 위험이 생기기 때문이다. 대상 컬럼은 WHERE·ORDER BY·LIKE에 쓰이지 않아
 * 원소 단위 인덱싱이 필요 없다.
 *
 * <p>{@link JsonMapper}는 Spring Boot가 자동 구성한 전역 빈(Jackson 3)을 재사용한다
 * ({@code OcrJobOutboxPortImpl} 관례). 역직렬화는 제네릭 소거 이슈가 없는 {@code String[].class}로 받고,
 * 원소에 {@code null}이 섞여도 NPE가 나지 않도록 {@code List.of(arr)}가 아니라
 * {@link Arrays#asList(Object[])}로 감싼다(§6).
 *
 * <p>null 속성 → null 컬럼, 빈 리스트 → {@code "[]"}를 암호화한 non-null 컬럼으로 구분을 보존한다.
 */
public abstract class PiiStringListConverter implements AttributeConverter<List<String>, byte[]> {

  private final PiiCipher cipher;
  private final JsonMapper jsonMapper;
  private final PiiAad aad;

  protected PiiStringListConverter(PiiCipher cipher, JsonMapper jsonMapper, String table, String column) {
    // Spring 빈이 아니라 Hibernate no-arg 폴백으로 생성되면 조용한 NPE 대신 여기서 명확히 실패한다(§12.2 C18).
    if (cipher == null || jsonMapper == null) {
      throw new IllegalStateException("PiiCipher/JsonMapper 주입 실패 — 컨버터가 Spring 빈으로 생성되지 않았습니다");
    }
    this.cipher = cipher;
    this.jsonMapper = jsonMapper;
    this.aad = PiiAad.ofColumn(table, column);
  }

  @Override
  public byte[] convertToDatabaseColumn(List<String> attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return cipher.encryptText(jsonMapper.writeValueAsString(attribute), aad);
    } catch (JacksonException ex) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }

  @Override
  public List<String> convertToEntityAttribute(byte[] dbData) {
    if (dbData == null) {
      return null;
    }
    String json = cipher.decryptText(dbData, aad);
    try {
      return Arrays.asList(jsonMapper.readValue(json, String[].class));
    } catch (JacksonException ex) {
      // 복호문(json)을 예외 메시지에 실으면 GlobalExceptionHandler의 catch-all 로그로 평문이 샌다 — 정규화해서 차단.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }
}
