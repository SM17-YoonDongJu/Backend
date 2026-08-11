package com.soma.backend.global.security.crypto.converter;

import jakarta.persistence.AttributeConverter;

import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * PII 문자열 컬럼(`text`/`varchar` → `bytea`)의 AES-256-GCM 봉투 암복호화 베이스(design.md §7.1).
 *
 * <p>하위 클래스는 컬럼 하나당 하나씩 두고 생성자에서 {@code table}·{@code column}을 넘겨 AAD를 고정한다.
 * AAD가 컬럼에 바인딩되므로 다른 컬럼의 암호문을 옮겨 심어도 복호화가 실패한다.
 * 값이 {@code null}이면 암호화하지 않고 {@code null} 컬럼으로 왕복시킨다(null ↔ null 구분 보존).
 * 키·평문·암호문은 어디에도 로깅하지 않는다.
 */
public abstract class PiiStringConverter implements AttributeConverter<String, byte[]> {

  private final PiiCipher cipher;
  private final PiiAad aad;

  protected PiiStringConverter(PiiCipher cipher, String table, String column) {
    // Spring 빈이 아니라 Hibernate no-arg 폴백으로 생성되면 조용한 NPE 대신 여기서 명확히 실패한다(§12.2 C18).
    if (cipher == null) {
      throw new IllegalStateException("PiiCipher 주입 실패 — 컨버터가 Spring 빈으로 생성되지 않았습니다");
    }
    this.cipher = cipher;
    this.aad = PiiAad.ofColumn(table, column);
  }

  @Override
  public byte[] convertToDatabaseColumn(String attribute) {
    return attribute == null ? null : cipher.encryptText(attribute, aad);
  }

  @Override
  public String convertToEntityAttribute(byte[] dbData) {
    return dbData == null ? null : cipher.decryptText(dbData, aad);
  }
}
