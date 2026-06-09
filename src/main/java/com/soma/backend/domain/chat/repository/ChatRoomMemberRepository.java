package com.soma.backend.domain.chat.repository;

import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, UUID> {

    Optional<ChatRoomMember> findByChatRoomIdAndUserId(UUID roomId, UUID userId);

    boolean existsByChatRoomIdAndUserId(UUID chatRoomId, UUID userId);

    List<ChatRoomMember> findAllByChatRoomId(UUID chatRoomId);

    @Query("SELECT crm.chatRoom FROM ChatRoomMember crm "
            + "WHERE crm.userId IN :userIds AND crm.chatRoom.type = 'DIRECT' "
            + "GROUP BY crm.chatRoom HAVING COUNT(DISTINCT crm.userId) = 2")
    List<ChatRoom> findDirectRoomByTwoUsers(@Param("userIds") List<UUID> userIds);
}
