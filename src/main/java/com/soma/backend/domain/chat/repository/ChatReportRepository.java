package com.soma.backend.domain.chat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.chat.entity.ChatReport;

/** ChatReport(채팅방 신고) Aggregate Spring Data JPA 리포지토리. 접수 전용 — 조회 API는 범위 밖. */
public interface ChatReportRepository extends JpaRepository<ChatReport, UUID> {
}
