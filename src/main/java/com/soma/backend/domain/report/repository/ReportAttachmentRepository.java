package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.report.entity.ReportAttachment;

/** ReportAttachment(Report Aggregate 내부 구성요소) Spring Data JPA 리포지토리. */
public interface ReportAttachmentRepository extends JpaRepository<ReportAttachment, UUID> {

  List<ReportAttachment> findAllByReportId(UUID reportId);
}
