package com.soma.backend.domain.adjuster.dto;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.adjuster.entity.AdjusterApplication;
import com.soma.backend.domain.adjuster.entity.AdjusterApplicationDocument;

/**
 * 내 자격 신청 상태 조회 응답(GET /users/adjuster-applications/me).
 * documents는 문서 종류(LICENSE→REGISTRATION) 순으로 정렬해 안정적으로 노출한다.
 */
public record AdjusterApplicationResponse(
    UUID applicationId,
    String status,
    LocalDateTime submittedAt,
    String name,
    @Nullable String speciality,
    @Nullable String licenseNo,
    List<Document> documents,
    @Nullable LocalDateTime rejectedAt,
    @Nullable String rejectReason) {

  public static AdjusterApplicationResponse from(AdjusterApplication application) {
    List<Document> documents = application.getDocuments().stream()
        .sorted(Comparator.comparing(AdjusterApplicationDocument::getDocType))
        .map(Document::from)
        .toList();
    return new AdjusterApplicationResponse(
        application.getId(),
        application.getStatus().name(),
        application.getCreatedAt(),
        application.getName(),
        application.getSpeciality(),
        application.getLicenseNo(),
        documents,
        application.getRejectedAt(),
        application.getRejectReason());
  }

  /** 문서별 심사 상태 항목(documents[]). */
  public record Document(String type, String status) {

    public static Document from(AdjusterApplicationDocument document) {
      return new Document(document.getDocType().name(), document.getStatus().name());
    }
  }
}
