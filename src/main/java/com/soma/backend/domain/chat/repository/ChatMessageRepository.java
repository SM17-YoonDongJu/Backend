package com.soma.backend.domain.chat.repository;

import com.soma.backend.domain.chat.entity.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("SELECT m FROM ChatMessage m WHERE m.roomId = :roomId "
            + "AND (m.createdAt < :cursorTime OR (m.createdAt = :cursorTime AND m.id < :cursorId)) "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<ChatMessage> findByRoomIdWithCursor(@Param("roomId") UUID roomId,
                                             @Param("cursorTime") Instant cursorTime,
                                             @Param("cursorId") UUID cursorId,
                                             Pageable pageable);

    @Query("SELECT m FROM ChatMessage m WHERE m.roomId = :roomId "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<ChatMessage> findByRoomIdLatest(@Param("roomId") UUID roomId, Pageable pageable);

    Optional<ChatMessage> findTopByRoomIdOrderByCreatedAtDesc(UUID roomId);
}
