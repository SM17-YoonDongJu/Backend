package com.soma.backend.global.security.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * PII 암호문 봉투(envelope) 포맷의 단일 진실. 상수 정의와 헤더 조립·파싱만 담당하고 암복호화는 하지 않는다
 * (암복호화는 {@link PiiCipher}).
 *
 * <pre>
 * offset  길이  내용
 * ------  ----  ------------------------------------------------------------
 * 0       1     version      = 0x01
 * 1       1     aadScope     = 0x01(TABLE_COLUMN) | 0x02(TABLE_COLUMN_ROW, 2단계 예약)
 * 2       2     keyVersion   (big-endian unsigned short, encryption_keys.key_version)
 * 4       12    nonce        (SecureRandom, 값마다 새로 생성)
 * 16      N     ciphertext ‖ GCM tag(16B)
 * </pre>
 *
 * <p>총 오버헤드는 32바이트(헤더 16 + 태그 16)다. AAD는 {@code 헤더 4바이트 ‖ UTF-8(PiiAad.canonical())}로,
 * 헤더까지 인증 대상에 포함시켜 {@code keyVersion}·{@code aadScope} 플립 같은 헤더 변조를 GCM 태그가 잡아낸다.
 *
 * <p>{@code keyVersion}을 봉투에 실어 두면 키 로테이션 이후에도 구 버전 DEK를 찾아 기존 암호문을 그대로
 * 복호화할 수 있다(1단계는 항상 1).
 */
public final class PiiEnvelope {

  public static final byte VERSION_1 = 0x01;
  public static final byte SCOPE_TABLE_COLUMN = 0x01;
  public static final byte SCOPE_TABLE_COLUMN_ROW = 0x02;
  public static final int HEADER_LENGTH = 4;
  public static final int NONCE_LENGTH = 12;
  public static final int TAG_LENGTH_BITS = 128;
  public static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

  /** 봉투가 최소한 가져야 하는 길이 — 이보다 짧으면 파싱조차 시도하지 않는다(인덱스 초과 방지). */
  public static final int MIN_LENGTH = HEADER_LENGTH + NONCE_LENGTH + TAG_LENGTH_BYTES;

  private static final int VERSION_OFFSET = 0;
  private static final int SCOPE_OFFSET = 1;
  private static final int KEY_VERSION_OFFSET = 2;
  private static final int NONCE_OFFSET = HEADER_LENGTH;
  private static final int CIPHERTEXT_OFFSET = HEADER_LENGTH + NONCE_LENGTH;
  private static final int MIN_KEY_VERSION = 1;
  private static final int MAX_KEY_VERSION = 0xFFFF;
  private static final int UNSIGNED_BYTE_MASK = 0xFF;
  private static final int BYTE_BITS = 8;

  private PiiEnvelope() {
  }

  /**
   * 봉투 헤더 4바이트를 만든다. {@code keyVersion}은 unsigned short로 기록하므로 표현 범위를 벗어나면
   * 봉투를 만들 수 없다(키 로테이션이 65535회를 넘으면 포맷 version을 올려야 한다).
   */
  static byte[] header(byte scope, int keyVersion) {
    if (keyVersion < MIN_KEY_VERSION || keyVersion > MAX_KEY_VERSION) {
      throw new IllegalArgumentException("keyVersion이 봉투 헤더(unsigned short) 표현 범위를 벗어났습니다");
    }
    return new byte[] {
      VERSION_1,
      scope,
      (byte) ((keyVersion >>> BYTE_BITS) & UNSIGNED_BYTE_MASK),
      (byte) (keyVersion & UNSIGNED_BYTE_MASK)
    };
  }

  /** GCM에 넘길 AAD 바이트 = 헤더 ‖ UTF-8(canonical). 헤더를 포함해 헤더 변조까지 인증한다. */
  static byte[] aad(byte[] header, PiiAad piiAad) {
    byte[] canonical = piiAad.canonical().getBytes(StandardCharsets.UTF_8);
    byte[] combined = new byte[header.length + canonical.length];
    System.arraycopy(header, 0, combined, 0, header.length);
    System.arraycopy(canonical, 0, combined, header.length, canonical.length);
    return combined;
  }

  /** 헤더 ‖ nonce ‖ (ciphertext‖tag)를 하나의 봉투 바이트 배열로 결합한다. */
  static byte[] pack(byte[] header, byte[] nonce, byte[] sealed) {
    byte[] envelope = new byte[header.length + nonce.length + sealed.length];
    System.arraycopy(header, 0, envelope, 0, header.length);
    System.arraycopy(nonce, 0, envelope, header.length, nonce.length);
    System.arraycopy(sealed, 0, envelope, header.length + nonce.length, sealed.length);
    return envelope;
  }

  /** 봉투 포맷 version 바이트. 호출 전에 {@link #MIN_LENGTH} 검증이 끝나 있어야 한다. */
  static byte version(byte[] envelope) {
    return envelope[VERSION_OFFSET];
  }

  /** 봉투에 기록된 AAD 스코프 바이트. */
  static byte scope(byte[] envelope) {
    return envelope[SCOPE_OFFSET];
  }

  /** 봉투에 기록된 키 버전(big-endian unsigned short). */
  static int keyVersion(byte[] envelope) {
    int high = envelope[KEY_VERSION_OFFSET] & UNSIGNED_BYTE_MASK;
    int low = envelope[KEY_VERSION_OFFSET + 1] & UNSIGNED_BYTE_MASK;
    return (high << BYTE_BITS) | low;
  }

  static byte[] headerOf(byte[] envelope) {
    return Arrays.copyOfRange(envelope, 0, HEADER_LENGTH);
  }

  static byte[] nonceOf(byte[] envelope) {
    return Arrays.copyOfRange(envelope, NONCE_OFFSET, NONCE_OFFSET + NONCE_LENGTH);
  }

  static byte[] ciphertextOf(byte[] envelope) {
    return Arrays.copyOfRange(envelope, CIPHERTEXT_OFFSET, envelope.length);
  }
}
