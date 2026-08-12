package com.soma.backend.global.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 조회 조건(WHERE 동등비교)으로 쓰이는 PII 컬럼용 HMAC-SHA256 블라인드 인덱스(이슈 #232).
 *
 * <p>{@link PiiCipher}(AES-256-GCM)는 값마다 nonce가 달라 같은 평문도 매번 다른 암호문이 나오므로
 * 동등비교 조회에 쓸 수 없다. 이 컴포넌트는 같은 평문이면 항상 같은 다이제스트가 나오는 결정적(deterministic)
 * HMAC을 만들어, 그 다이제스트를 별도 컬럼(예: {@code phone_number_hmac})에 저장해 조회 인덱스로 쓴다.
 * 실제 값 자체는 여전히 {@link PiiCipher}로 암호화해 원본 컬럼에 저장한다(다이제스트는 조회 전용, 값 복원 불가).
 *
 * <p>키는 {@link PiiCipher}가 쓰는 AES DEK와 <b>완전히 분리</b>된 별도 purpose({@code PII_HMAC})의 DEK를
 * 쓴다(알고리즘 목적이 다른 키를 재사용하지 않는다는 원칙). {@link PiiAad#canonical()}로 테이블·컬럼을 다이제스트
 * 입력에 묶어, 같은 값이라도 컬럼이 다르면 다이제스트가 달라지게 한다(컬럼 간 무지개표 재사용 방지).
 *
 * <p><b>키 로테이션 한계:</b> HMAC 키를 회전하면 기존에 저장된 다이제스트는 새 키로 재계산하기 전까지 조회되지
 * 않는다(AES처럼 봉투에 keyVersion을 실어 자동으로 옛 키를 찾아주는 구조가 아니다). 로테이션은 전체 재인덱싱
 * 배치가 필요하며 현재 범위 밖이다.
 */
@Component
public class PiiHmac {

  private static final String ALGORITHM = "HmacSHA256";

  private final PiiDataKeyProvider keyProvider;

  public PiiHmac(@Qualifier("piiHmacDataKeyProvider") PiiDataKeyProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  /** 평문의 HMAC-SHA256 다이제스트(32바이트)를 계산한다. {@code aad}로 테이블·컬럼을 다이제스트에 묶는다. */
  public byte[] hmac(String plaintext, PiiAad aad) {
    if (plaintext == null || aad == null) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(keyProvider.active().key().getEncoded(), ALGORITHM));
      mac.update(aad.canonical().getBytes(StandardCharsets.UTF_8));
      // 구분자 없이 이어붙이면 ("users:phone_number" + "1" vs "users:phone_numbe" + "r1") 서로 다른
      // (table, column, value) 조합이 같은 입력 바이트열로 충돌할 수 있어 NUL 구분자로 경계를 고정한다.
      mac.update((byte) 0);
      return mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      // 원인값(키·평문)은 절대 싣지 않는다 — 예외 타입만으로 관측한다.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }
}
