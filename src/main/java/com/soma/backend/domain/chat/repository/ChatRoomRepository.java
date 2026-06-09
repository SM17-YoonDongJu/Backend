package com.soma.backend.domain.chat.repository;

import com.soma.backend.domain.chat.entity.ChatRoom;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, UUID> {

    @Query("SELECT cr FROM ChatRoom cr JOIN cr.members m "
            + "WHERE m.userId = :userId ORDER BY cr.updatedAt DESC")
    List<ChatRoom> findByMemberUserId(@Param("userId") UUID userId, Pageable pageable);
}
