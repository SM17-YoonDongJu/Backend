package com.soma.backend.domain.report.entity;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험사 기준정보(insurers) 읽기 전용 매핑. 이 앱은 쓰지 않고 검수 화면 맥락 조회의 조인 대상으로만 쓴다 —
 * 매핑 전에는 이 테이블 때문에 맥락 조회가 native 쿼리로 남아 있었다.
 */
@Entity
@Immutable
@Table(name = "insurers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Insurer {

  @Id
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;
}
