package com.soma.backend.domain.report.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 재업로드 원인 VO(불변) — 재업로드 사유와 확인하지 못한 필수 서류의 묶음. {@code ai.claim_readiness}의 두 컬럼
 * ({@code reupload_reason}·{@code missing_doc_types})을 함께 번역해야 문구 하나가 정해지므로 값 하나로 묶는다 — 조회
 * 응답({@link ReportAnalysis#failureMessage()})과 푸시가 같은 객체의 {@link #userMessage()}를 불러 단일 진실을 지킨다.
 *
 * <p>ACL 진입점은 {@link #from(String, List)} 하나다. 사유별 안내가 없는 값({@code unreadable}·NULL·모르는 값)은
 * 이 VO를 만들지 않고 {@code null}을 돌려준다 — 그때 호출자는 기존 일반 안내를 낸다.
 *
 * @param reason           재업로드 사유. null 불가
 * @param missingDocuments 확인하지 못한 필수 서류. {@link ReuploadReason#MISSING_REQUIRED_DOCUMENTS}일 때만 의미가 있어
 *                         그 밖의 사유면 빈 집합으로 정규화된다. 서류를 특정하지 못했으면 빈 집합이다(null 아님)
 */
public record ReuploadCause(ReuploadReason reason, Set<RequiredDocumentType> missingDocuments) {

  private static final Logger log = LoggerFactory.getLogger(ReuploadCause.class);

  /** 보험증권·진단서를 둘 다 확인하지 못했을 때의 문구. 조사가 조합마다 달라 조립하지 않고 고정 문장으로 둔다. */
  private static final String BOTH_MISSING_MESSAGE =
      "업로드하신 문서에서 보험증권과 진단서를 확인하지 못했어요. 두 서류를 다시 올려주세요.";

  private static final Set<RequiredDocumentType> BOTH_REQUIRED_DOCUMENTS =
      Collections.unmodifiableSet(EnumSet.of(RequiredDocumentType.POLICY, RequiredDocumentType.DIAGNOSIS));

  /**
   * 사유는 필수이고, 확인하지 못한 서류는 불변 집합으로 복사한다. 필수 서류 미확인 사유가 아니면 서류 집합은 의미가
   * 없어 비운다 — 의미 없는 값을 들고 있지 않는다.
   */
  public ReuploadCause {
    Objects.requireNonNull(reason, "reason");
    boolean meaningful = reason == ReuploadReason.MISSING_REQUIRED_DOCUMENTS
        && missingDocuments != null && !missingDocuments.isEmpty();
    missingDocuments = meaningful ? Collections.unmodifiableSet(EnumSet.copyOf(missingDocuments)) : Set.of();
  }

  /**
   * ACL 진입점 — {@code ai.claim_readiness}의 두 원값을 재업로드 원인으로 옮긴다.
   *
   * <p>사유를 {@link ReuploadReason#from(String)}으로 번역해 {@code null}이면(재업로드 아님·{@code unreadable}·모르는
   * 값) {@code null}이다. {@code missing_doc_types}는 필수 서류 미확인 사유일 때만 번역한다 — 다른 사유의 행에 값이
   * 채워져 있어도 보지 않는다. 원소가 하나라도 {@code null}이거나 모르는 값이면 일부 서류만 짚지 않고 경고를 남긴 뒤
   * 서류를 특정하지 않는다(일반 미확인 문구). 중복 원소는 집합이 흡수한다. 원소는 유형 코드라 로그에 남겨도 개인정보가
   * 아니다.
   *
   * @param rawReason          {@code reupload_reason} 원값
   * @param rawMissingDocTypes {@code missing_doc_types} 원값(빈 배열·null 가능)
   * @return 사유별 안내를 낼 원인. 기존 일반 안내로 낼 경우 {@code null}
   */
  public static @Nullable ReuploadCause from(@Nullable String rawReason, @Nullable List<String> rawMissingDocTypes) {
    ReuploadReason reason = ReuploadReason.from(rawReason);
    if (reason == null) {
      return null;
    }
    if (reason != ReuploadReason.MISSING_REQUIRED_DOCUMENTS) {
      return new ReuploadCause(reason, Set.of());
    }
    return new ReuploadCause(reason, missingDocuments(rawMissingDocTypes));
  }

  /** {@code missing_doc_types} 원소를 번역한다. 모르는 원소가 섞이면 경고 후 빈 집합(서류 미특정)이다. */
  private static Set<RequiredDocumentType> missingDocuments(@Nullable List<String> rawMissingDocTypes) {
    if (rawMissingDocTypes == null || rawMissingDocTypes.isEmpty()) {
      return Set.of();
    }
    Set<RequiredDocumentType> types = EnumSet.noneOf(RequiredDocumentType.class);
    List<String> unknownValues = new ArrayList<>();
    for (String rawType : rawMissingDocTypes) {
      RequiredDocumentType type = RequiredDocumentType.from(rawType);
      if (type == null) {
        unknownValues.add(rawType);
      } else {
        types.add(type);
      }
    }
    if (!unknownValues.isEmpty()) {
      log.warn("알 수 없는 missing_doc_types={} — 확인하지 못한 서류를 특정하지 않는다(AI 계약 확장 여부 확인 필요)",
          unknownValues);
      return Set.of();
    }
    return types;
  }

  /**
   * 사용자 노출 문구(조회 응답·푸시·인앱 알림함 공통 출처). 필수 서류 미확인 사유는 확인하지 못한 서류가 하나면 그
   * 서류의 문구, 정확히 보험증권·진단서 둘이면 두 서류 문구, 서류를 특정하지 못했으면 사유의 기본 문구다. 그 밖의
   * 사유는 사유의 문구다.
   */
  public String userMessage() {
    if (reason != ReuploadReason.MISSING_REQUIRED_DOCUMENTS) {
      return reason.getUserMessage();
    }
    if (missingDocuments.size() == 1) {
      return missingDocuments.iterator().next().getMissingMessage();
    }
    if (missingDocuments.equals(BOTH_REQUIRED_DOCUMENTS)) {
      return BOTH_MISSING_MESSAGE;
    }
    return reason.getUserMessage();
  }
}
