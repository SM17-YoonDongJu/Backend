package com.soma.backend.domain.chat.service;

import com.soma.backend.domain.chat.dto.JoinRoomResponse;
import com.soma.backend.domain.chat.dto.RoomListResponse;
import com.soma.backend.domain.chat.dto.RoomResponse;
import com.soma.backend.domain.chat.dto.RoomSummaryResponse;
import com.soma.backend.domain.chat.dto.CreateRoomRequest;
import com.soma.backend.domain.chat.dto.MemberResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomMember;
import com.soma.backend.domain.chat.entity.ChatRoomType;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomMemberRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final ChatMessageRepository chatMessageRepository;

    public RoomResponse createRoom(UUID creatorId, CreateRoomRequest req) {
        ChatRoomType type = ChatRoomType.valueOf(req.type());

        // 생성자 + 초대 멤버 중복 제거 (본인 중복 포함 방어)
        Set<UUID> memberIds = new LinkedHashSet<>();
        memberIds.add(creatorId);
        if (req.memberIds() != null) {
            memberIds.addAll(req.memberIds());
        }

        if (type == ChatRoomType.DIRECT) {
            validateDirectRoom(creatorId, req.memberIds(), memberIds);
        }

        ChatRoom room = ChatRoom.create(req.title(), type);
        for (UUID memberId : memberIds) {
            room.addMember(ChatRoomMember.create(memberId));
        }
        ChatRoom saved = chatRoomRepository.save(room);

        List<MemberResponse> members = saved.getMembers().stream()
                .map(MemberResponse::from)
                .toList();
        return RoomResponse.of(saved, members, null);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoom(UUID roomId, UUID requesterId, String requesterRole) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        assertMemberUnlessAdmin(roomId, requesterId, requesterRole);

        List<MemberResponse> members = chatRoomMemberRepository.findAllByChatRoomId(roomId).stream()
                .map(MemberResponse::from)
                .toList();
        ChatMessage lastMessage = chatMessageRepository
                .findTopByRoomIdOrderByCreatedAtDesc(roomId)
                .orElse(null);
        return RoomResponse.of(room, members, lastMessage);
    }

    @Transactional(readOnly = true)
    public RoomListResponse getRooms(UUID userId, int limit, String cursor) {
        // cursor 기반 페이지네이션은 updatedAt 정렬. 단순 limit 기반으로 다음 페이지 존재 여부 판단.
        List<ChatRoom> rooms = chatRoomRepository.findByMemberUserId(
                userId, PageRequest.of(0, limit + 1));

        boolean hasNext = rooms.size() > limit;
        List<ChatRoom> page = hasNext ? rooms.subList(0, limit) : rooms;

        List<RoomSummaryResponse> summaries = new ArrayList<>();
        for (ChatRoom room : page) {
            ChatMessage lastMessage = chatMessageRepository
                    .findTopByRoomIdOrderByCreatedAtDesc(room.getId())
                    .orElse(null);
            summaries.add(RoomSummaryResponse.of(room, lastMessage));
        }

        String nextCursor = hasNext
                ? page.get(page.size() - 1).getId().toString()
                : null;
        return new RoomListResponse(summaries, nextCursor);
    }

    public JoinRoomResponse joinRoom(UUID roomId, UUID userId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (room.getType() == ChatRoomType.DIRECT) {
            throw new BusinessException(ErrorCode.CHAT_DIRECT_JOIN_FORBIDDEN);
        }
        if (chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(ErrorCode.CHAT_ALREADY_MEMBER);
        }

        ChatRoomMember member = ChatRoomMember.create(userId);
        room.addMember(member);
        chatRoomMemberRepository.save(member);
        return JoinRoomResponse.from(member);
    }

    public void leaveRoom(UUID roomId, UUID userId) {
        if (!chatRoomRepository.existsById(roomId)) {
            throw new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        ChatRoomMember member = chatRoomMemberRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER));
        chatRoomMemberRepository.delete(member);
    }

    @Transactional(readOnly = true)
    public void assertMember(UUID roomId, UUID userId) {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
        }
    }

    @Transactional(readOnly = true)
    public void assertMemberUnlessAdmin(UUID roomId, UUID userId, String role) {
        if (!"ADMIN".equals(role)) {
            assertMember(roomId, userId);
        }
    }

    private void validateDirectRoom(UUID creatorId, List<UUID> requestedMemberIds, Set<UUID> distinctMembers) {
        // DIRECT: 생성자 외 정확히 1명 → 전체 2명
        if (requestedMemberIds == null || requestedMemberIds.size() != 1
                || distinctMembers.size() != 2) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_MEMBER_COUNT);
        }
        List<UUID> userIds = new ArrayList<>(distinctMembers);
        List<ChatRoom> existing = chatRoomMemberRepository.findDirectRoomByTwoUsers(userIds);
        if (!existing.isEmpty()) {
            throw new BusinessException(ErrorCode.CHAT_DUPLICATE_DIRECT_ROOM);
        }
    }
}
