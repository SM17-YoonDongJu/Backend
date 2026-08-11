package com.soma.backend.global.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * PII 컬럼(자유서술·카탈로그성 개인정보) 전용 AES-256-GCM 암복호화기. 결과는 base64 문자열이 아니라
 * {@link PiiEnvelope} 포맷의 원시 바이트 봉투이며, {@code bytea} 컬럼에 그대로 저장된다(base64 33% 팽창 회피).
 *
 * <p>{@link AesGcmCipher}(Apple refresh_token 전용)와는 <b>키·클래스가 완전히 분리</b>돼 있다. DEK는
 * {@link PiiDataKeyProvider}가 공급하며 로컬/개발/테스트는 raw 키, 운영은 KMS 봉투암호화 경로를 탄다.
 *
 * <p>모든 암호화는 값마다 새 nonce를 쓰고(같은 평문도 매번 다른 암호문), AAD로 {@code {table}:{column}}을
 * 묶어 컬럼 간 암호문 치환을 차단한다. 복호화 시에는 다음을 순서대로 방어한다.
 *
 * <ol>
 *   <li>봉투 최소 길이 검증 — 짧으면 파싱하지 않는다(인덱스 초과 예외 누출 방지)</li>
 *   <li>포맷 version 일치 검증 — 알 수 없는 포맷은 즉시 거부</li>
 *   <li>AAD 스코프 일치 검증 — 행 바인딩(0x02) 봉투를 컬럼 바인딩(0x01)으로 읽는 다운그레이드 차단</li>
 *   <li>헤더를 포함한 AAD 재구성 후 GCM 태그 검증 — 헤더·암호문·태그 어느 바이트가 바뀌어도 실패</li>
 * </ol>
 *
 * <p><b>키·평문·복호문·암호문·AAD 원문은 어떤 경우에도 로깅하지 않으며 예외 메시지에도 싣지 않는다.</b>
 * 변조·키 불일치·포맷 오류는 원인을 구분하지 않고 모두 {@link ErrorCode#PII_CRYPTO_ERROR}로 던진다
 * (오라클 공격 표면 축소).
 */
@Component
public class PiiCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private final PiiDataKeyProvider keyProvider;
  private final SecureRandom secureRandom = new SecureRandom();

  public PiiCipher(PiiDataKeyProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  /**
   * 평문 바이트를 활성 DEK로 암호화해 봉투를 반환한다. 값마다 새 nonce를 생성한다.
   */
  public byte[] encrypt(byte[] plaintext, PiiAad aad) {
    if (plaintext == null || aad == null) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    PiiDataKeyProvider.ActiveDataKey active = keyProvider.active();
    try {
      byte[] header = PiiEnvelope.header(aad.scope(), active.version());
      byte[] nonce = new byte[PiiEnvelope.NONCE_LENGTH];
      secureRandom.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, active.key(), new GCMParameterSpec(PiiEnvelope.TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(PiiEnvelope.aad(header, aad));
      byte[] sealed = cipher.doFinal(plaintext);
      return PiiEnvelope.pack(header, nonce, sealed);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      // 원인값(키·평문·AAD)은 절대 싣지 않는다 — 예외 타입만으로 관측한다.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }

  /**
   * 봉투를 복호화한다. 헤더 파싱 → keyVersion으로 DEK 해석 → AAD 재구성 → GCM 태그 검증 순서로 진행하며,
   * 어느 단계에서 실패하든 {@link ErrorCode#PII_CRYPTO_ERROR}로 통일해 던진다.
   */
  public byte[] decrypt(byte[] envelope, PiiAad aad) {
    if (envelope == null || aad == null || envelope.length < PiiEnvelope.MIN_LENGTH) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    if (PiiEnvelope.version(envelope) != PiiEnvelope.VERSION_1) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    if (PiiEnvelope.scope(envelope) != aad.scope()) {
      // 행 바인딩 봉투를 컬럼 바인딩으로 읽으려는 다운그레이드(또는 그 반대) 시도.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    SecretKey key = resolveKey(PiiEnvelope.keyVersion(envelope));
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      GCMParameterSpec spec = new GCMParameterSpec(PiiEnvelope.TAG_LENGTH_BITS, PiiEnvelope.nonceOf(envelope));
      cipher.init(Cipher.DECRYPT_MODE, key, spec);
      cipher.updateAAD(PiiEnvelope.aad(PiiEnvelope.headerOf(envelope), aad));
      return cipher.doFinal(PiiEnvelope.ciphertextOf(envelope));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      // AEADBadTagException(변조·AAD 불일치·키 불일치)이 그대로 새어나가지 않도록 여기서 흡수한다.
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }

  /** 문자열을 UTF-8로 인코딩해 암호화한다. */
  public byte[] encryptText(String plaintext, PiiAad aad) {
    if (plaintext == null) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
    return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad);
  }

  /** 봉투를 복호화해 UTF-8 문자열로 되돌린다. */
  public String decryptText(byte[] envelope, PiiAad aad) {
    return new String(decrypt(envelope, aad), StandardCharsets.UTF_8);
  }

  /**
   * 봉투가 가리키는 키 버전의 DEK를 해석한다. 미등록·변조된 버전이면 provider가 IllegalStateException을
   * 던지는데, 이는 봉투 데이터 오류이므로 다른 실패와 동일하게 PII_CRYPTO_ERROR로 정규화한다.
   *
   * <p>{@code RuntimeException}까지 넓게 잡는 이유: KMS 경로({@link com.soma.backend.infra.kms
   * .KmsEnvelopePiiDataKeyProvider})가 던지는 AWS SDK 예외(예: {@code KmsException})는 메시지에
   * CMK ARN·request-id가 실려 있어, 정규화하지 않으면 catch-all 로그로 그대로 샌다.
   */
  private SecretKey resolveKey(int keyVersion) {
    try {
      return keyProvider.byVersion(keyVersion);
    } catch (RuntimeException ex) {
      throw new BusinessException(ErrorCode.PII_CRYPTO_ERROR);
    }
  }
}
