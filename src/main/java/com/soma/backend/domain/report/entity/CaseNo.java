package com.soma.backend.domain.report.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * 사람용 사건번호. 저장하지도 발급하지도 않고, 응답할 때 리포트 생성일과 리포트 ID로 만든다(#413).
 *
 * <p>형식은 {@code yyyyMMdd-XXXXXXXX}다. 앞은 리포트 {@code created_at}의 날짜이고, 뒤 8자리는 리포트 ID
 * ({@code UUID.toString()})의 앞 8자리를 대문자로 바꾼 값이다. 예: {@code 20261119-3F2B8C1E}. 날짜는
 * {@code created_at}({@code LocalDateTime}, JVM 기본 시간대)을 그대로 쓴다. 예전 발급기도 같은 시간대의 오늘 날짜를
 * 붙였으므로 기존 리포트의 날짜 부분은 바뀌지 않는다.
 *
 * <p>표시 전용이라 유일성을 보장하지 않는다. 리포트 ID가 랜덤(v4) UUID라 뒤 8자리(32비트)가 같은 날 겹칠 수 있다. 그날
 * 리포트가 n건이면 겹칠 확률은 대략 n²/2³³이다(1,000건이면 약 0.01%). 그래서 조회 키나 UNIQUE 제약으로 쓰지 않는다.
 */
public final class CaseNo {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private static final int ID_PREFIX_LENGTH = 8;

  /** 정적 메서드만 쓰는 유틸 클래스라 인스턴스를 만들지 않는다. */
  private CaseNo() {
  }

  /**
   * 리포트 생성 시각과 리포트 ID로 사건번호를 만든다.
   *
   * @param createdAt 리포트의 생성 시각({@code reports.created_at}). 검수 시각·메시지 시각 같은 다른 시각을 넘기면 안 된다
   * @param reportId 리포트 ID
   * @return {@code yyyyMMdd-XXXXXXXX}. 둘 중 하나라도 null이면 null이다(저장 전 엔티티이거나, 채팅 검색 방처럼 리포트를
   *         LEFT JOIN했는데 없는 경우)
   */
  public static @Nullable String of(@Nullable LocalDateTime createdAt, @Nullable UUID reportId) {
    if (createdAt == null || reportId == null) {
      return null;
    }
    String idPrefix = reportId.toString().substring(0, ID_PREFIX_LENGTH).toUpperCase(Locale.ROOT);
    return createdAt.format(DATE) + "-" + idPrefix;
  }
}
