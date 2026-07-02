package com.soma.backend.user.domain.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * USERS 최소 골격 엔티티(A1). report 컨텍스트가 조회 전용으로 참조한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

  @Id
  private UUID id;

  @Column(name = "role", nullable = false, length = 50)
  private String role;

  @Column(name = "nickname", length = 100)
  private String nickname;

  @Column(name = "status", length = 30)
  private String status;
}
