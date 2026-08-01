package com.soma.backend.domain.adjuster.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * 자격 신청 증빙 문서 1건의 심사 상태. {@link AdjusterApplication} Aggregate 내부 Entity로,
 * 외부 Repository로 직접 저장/삭제하지 않고 Root를 통해서만 생명주기를 관리한다.
 * application_id(FK)는 Root의 {@code @OneToMany @JoinColumn}이 채운다.
 */
@Entity
@Table(name = "adjuster_application_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterApplicationDocument extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "doc_type", nullable = false, length = 20)
  private DocumentType docType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DocumentStatus status;

  private AdjusterApplicationDocument(DocumentType docType, DocumentStatus status) {
    this.docType = docType;
    this.status = status;
  }

  /** 심사 대기(PENDING) 문서 생성 — 신청 접수 시 Root가 문서 종류별로 호출한다. */
  static AdjusterApplicationDocument pending(DocumentType docType) {
    return new AdjusterApplicationDocument(docType, DocumentStatus.PENDING);
  }
}
