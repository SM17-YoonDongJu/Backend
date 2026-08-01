package com.soma.backend.domain.upload.entity;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 업로드 용도 값 객체(VO). 각 용도가 소유하는 S3 key prefix·허용 MIME 화이트리스트·최대 파일 크기를
 * 캡슐화하고, 허용되지 않은 content_type이나 용량 초과를 거부하는 불변식을 리치 도메인으로 표현한다.
 * DB·JSON 모두 소문자 문자열 값을 사용한다(report 도메인 AccidentType 선례와 동일한
 * @JsonValue/@JsonCreator 관례).
 */
public enum UploadPurpose {
  REPORT_DOCUMENT("report_document", "report-documents", Types.DOCUMENT, 20L * 1024 * 1024),
  LICENSE("license", "licenses", Types.DOCUMENT, 20L * 1024 * 1024),
  REGISTRATION("registration", "registrations", Types.DOCUMENT, 20L * 1024 * 1024),
  AVATAR("avatar", "avatars", Types.IMAGE, 5L * 1024 * 1024);

  private final String value;
  private final String keyPrefix;
  private final Set<String> allowedContentTypes;
  private final long maxSizeBytes;

  UploadPurpose(String value, String keyPrefix, Set<String> allowedContentTypes, long maxSizeBytes) {
    this.value = value;
    this.keyPrefix = keyPrefix;
    this.allowedContentTypes = allowedContentTypes;
    this.maxSizeBytes = maxSizeBytes;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static UploadPurpose from(String value) {
    for (UploadPurpose purpose : values()) {
      if (purpose.value.equals(value)) {
        return purpose;
      }
    }
    throw new IllegalArgumentException("Unknown upload purpose: " + value);
  }

  /** content_type이 이 용도의 허용 MIME 화이트리스트에 속하는지 여부. */
  public boolean allows(String contentType) {
    return allowedContentTypes.contains(contentType);
  }

  /** 이 용도의 S3 object key prefix. */
  public String keyPrefix() {
    return keyPrefix;
  }

  /** 이 용도의 최대 허용 파일 크기(바이트). 서버 경유 업로드에서 실제 바이트 크기로 강제한다. */
  public long maxSizeBytes() {
    return maxSizeBytes;
  }

  /**
   * MIME 화이트리스트 홀더. 열거형 상수는 다른 static 필드보다 먼저 초기화되므로, 화이트리스트를
   * UploadPurpose의 직접 static 필드로 두고 생성자에서 참조하면 초기화 순서상 null이 넘어간다.
   * 중첩 홀더 클래스로 분리해 이 초기화 순서 함정을 피한다.
   */
  private static final class Types {
    private static final Set<String> IMAGE = Set.of("image/jpeg", "image/png");
    private static final Set<String> DOCUMENT =
        Set.of("application/pdf", "image/jpeg", "image/png");

    private Types() {
    }
  }
}
