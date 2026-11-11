package com.soma.backend.global.security.crypto;

import java.util.UUID;

/**
 * PII 봉투암호화의 AAD(Additional Authenticated Data) 성분을 담는 값 객체(VO).
 *
 * <p>암호문을 "어느 테이블의 어느 컬럼 값인지"에 묶어, 같은 키로 암호화된 다른 컬럼의 암호문을 옮겨 심는
 * 오배치·치환 공격을 GCM 태그 검증 단계에서 차단한다. 예를 들어
 * {@code user_claims:additional_information} 암호문을 {@code reports:treatment} 자리에 넣으면 AAD가 달라져
 * 복호화가 실패한다.
 *
 * <p>1단계는 컬럼 바인딩({@link #ofColumn})만 쓴다. 행 바인딩({@link #ofRow})은 봉투 포맷과 API에만 예약해
 * 두고 실제 적용은 2단계로 미룬다 — JPA {@code AttributeConverter}가 값만 받고 엔티티·PK를 볼 수 없기
 * 때문이다(design.md §3.3).
 *
 * @param table  대상 테이블명(snake_case, DB 실제 이름)
 * @param column 대상 컬럼명(snake_case, DB 실제 이름)
 * @param rowId  행 식별자. 1단계는 항상 {@code null}(컬럼 바인딩)
 */
public record PiiAad(String table, String column, UUID rowId) {

  public PiiAad {
    if (table == null || table.isBlank() || column == null || column.isBlank()) {
      throw new IllegalArgumentException("PiiAad의 table·column은 필수입니다");
    }
  }

  /** 컬럼 바인딩 AAD(1단계 기본) — 행 성분 없음. */
  public static PiiAad ofColumn(String table, String column) {
    return new PiiAad(table, column, null);
  }

  /** 행 바인딩 AAD(2단계 예약, design.md §3.3). */
  public static PiiAad ofRow(String table, String column, UUID rowId) {
    return new PiiAad(table, column, rowId);
  }

  /** 봉투 헤더에 기록될 AAD 스코프 바이트. */
  public byte scope() {
    return rowId == null ? PiiEnvelope.SCOPE_TABLE_COLUMN : PiiEnvelope.SCOPE_TABLE_COLUMN_ROW;
  }

  /** GCM AAD 문자열 성분 — {@code "{table}:{column}"} 또는 {@code "{table}:{column}:{row_id}"}. */
  public String canonical() {
    return rowId == null ? table + ":" + column : table + ":" + column + ":" + rowId;
  }
}
