package com.soma.backend.domain.chat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.chat.entity.ChatMessage;

/** ChatMessage(append-only) Spring Data JPA 리포지토리 + 커서 페이지네이션 QueryDSL 프래그먼트. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID>, ChatMessageRepositoryCustom {
}
