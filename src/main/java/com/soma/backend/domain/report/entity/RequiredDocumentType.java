package com.soma.backend.domain.report.entity;

import org.jspecify.annotations.Nullable;

/**
 * 재업로드 안내가 짚는 필수 서류 유형(ACL). {@code ai.claim_readiness.missing_doc_types}(text[], AI 010) 원소를 도메인
 * 개념으로 번역한다. 원소는 "사용자가 안 올린 서류"가 아니라 <b>"올렸는데 인식하지 못한 필수 유형"</b>이다(010 주석) —
 * 그래서 문구도 "빠진 서류"가 아니라 "확인하지 못한" 서류로 서술한다.
 *
 * <p>두 번째 인자는 "이 유형 하나만 확인하지 못했을 때"의 문구다. 값을 늘리면 그 문구를 함께 정의하도록 컴파일러가
 * 강제한다. 여러 유형의 조합 문구는 조사가 조합마다 달라 코드에서 조립하지 않고 {@link ReuploadCause#userMessage()}가
 * 고정 문장으로 고른다.
 */
public enum RequiredDocumentType {

  /** {@code policy} — 보험증권. */
  POLICY("policy", "업로드하신 문서에서 보험증권을 확인하지 못했어요. 보험증권을 다시 올려주세요."),

  /** {@code diagnosis} — 진단서. */
  DIAGNOSIS("diagnosis", "업로드하신 문서에서 진단서를 확인하지 못했어요. 진단서를 다시 올려주세요.");

  private final String externalValue;
  private final String missingMessage;

  RequiredDocumentType(String externalValue, String missingMessage) {
    this.externalValue = externalValue;
    this.missingMessage = missingMessage;
  }

  /** 이 유형 하나만 확인하지 못했을 때의 사용자 노출 문구. */
  public String getMissingMessage() {
    return missingMessage;
  }

  /**
   * ACL 번역 — {@code missing_doc_types} 원소 하나를 유형으로 옮긴다. 값과 정확히 일치할 때만 유형이고(대소문자 구분),
   * {@code null}이거나 모르는 값이면 {@code null}이다. 경고 로그는 원소 묶음을 보는 {@link ReuploadCause#from}이 한 번만
   * 남긴다.
   *
   * @param externalValue {@code missing_doc_types} 원소 원값
   * @return 아는 유형이면 그 유형, 아니면 {@code null}
   */
  public static @Nullable RequiredDocumentType from(@Nullable String externalValue) {
    if (externalValue == null) {
      return null;
    }
    for (RequiredDocumentType type : values()) {
      if (type.externalValue.equals(externalValue)) {
        return type;
      }
    }
    return null;
  }
}
