package com.soma.backend.global.security.crypto.converter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import jakarta.persistence.AttributeConverter;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * PII 날짜 컬럼(`date` → `bytea`)의 AES-256-GCM 봉투 암복호화 베이스(design.md §7.1).
 *
 * <p>{@link DateTimeFormatter#ISO_LOCAL_DATE}로 {@code "2026-08-10"} 문자열로 직렬화한 뒤 암호화한다.
 * 암호화 이후에는 DB 레벨 날짜 범위 조회가 불가능해지므로, 조회 조건으로 쓰이지 않는 컬럼에만 적용한다(§A7).
 */
public abstract class PiiLocalDateConverter implements AttributeConverter<LocalDate, byte[]> {

  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

  private final PiiCipher cipher;
  private final PiiAad aad;

  protected PiiLocalDateConverter(PiiCipher cipher, String table, String column) {
    // Spring 빈이 아니라 Hibernate no-arg 폴백으로 생성되면 조용한 NPE 대신 여기서 명확히 실패한다(§12.2 C18).
    if (cipher == null) {
      throw new IllegalStateException("PiiCipher 주입 실패 — 컨버터가 Spring 빈으로 생성되지 않았습니다");
    }
    this.cipher = cipher;
    this.aad = PiiAad.ofColumn(table, column);
  }

  @Override
  public byte[] convertToDatabaseColumn(LocalDate attribute) {
    return attribute == null ? null : cipher.encryptText(FORMATTER.format(attribute), aad);
  }

  @Override
  public LocalDate convertToEntityAttribute(byte[] dbData) {
    if (dbData == null) {
      return null;
    }
    try {
      return LocalDate.parse(cipher.decryptText(dbData, aad), FORMATTER);
    } catch (DateTimeParseException ex) {
      // 복호문을 예외 메시지에 실으면 GlobalExceptionHandler의 catch-all 로그로 평문이 샌다 — 정규화해서 차단.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }
}
