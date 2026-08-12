package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code reports.question}(사용자 질문 입력, 자유서술 PII) 컬럼 암복호화. AAD = {@code reports:question}.
 *
 * <p>{@code autoApply = false} — 모든 {@code String} 컬럼이 암호화되는 사고를 막기 위해 절대 켜지 않는다.
 * 적용 대상은 {@code Report} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class ReportQuestionConverter extends PiiStringConverter {

  public ReportQuestionConverter(PiiCipher cipher) {
    super(cipher, "reports", "question");
  }
}
