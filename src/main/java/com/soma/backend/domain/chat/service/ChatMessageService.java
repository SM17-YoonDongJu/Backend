package com.soma.backend.domain.chat.service;

import com.soma.backend.domain.chat.dto.MessageHistoryResponse;
import com.soma.backend.domain.chat.dto.MessageResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ChatMessageService {

    private static final String CURSOR_DELIMITER = "_";

    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomService chatRoomService;

    @Transactional(readOnly = true)
    public MessageHistoryResponse getMessages(
            UUID roomId, UUID requesterId, int limit, String cursor, String requesterRole) {
        chatRoomService.assertMemberUnlessAdmin(roomId, requesterId, requesterRole);

        List<ChatMessage> messages = (cursor == null || cursor.isBlank())
                ? chatMessageRepository.findByRoomIdLatest(roomId, PageRequest.of(0, limit + 1))
                : findWithCursor(roomId, cursor, limit);

        boolean hasNext = messages.size() > limit;
        List<ChatMessage> page = hasNext ? messages.subList(0, limit) : messages;

        List<MessageResponse> responses = page.stream()
                .map(MessageResponse::from)
                .toList();

        String nextCursor = null;
        if (hasNext && !page.isEmpty()) {
            ChatMessage last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last);
        }
        return new MessageHistoryResponse(responses, nextCursor, hasNext);
    }

    public ChatMessage saveMessage(UUID roomId, UUID senderId, String content, ChatMessageType type) {
        chatRoomService.assertMember(roomId, senderId);
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
        }
        ChatMessage message = ChatMessage.create(roomId, senderId, type, content);
        return chatMessageRepository.save(message);
    }

    private List<ChatMessage> findWithCursor(UUID roomId, String cursor, int limit) {
        String[] parts = cursor.split(CURSOR_DELIMITER, 2);
        if (parts.length != 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            Instant cursorTime = Instant.parse(parts[0]);
            UUID cursorId = UUID.fromString(parts[1]);
            return chatMessageRepository.findByRoomIdWithCursor(
                    roomId, cursorTime, cursorId, PageRequest.of(0, limit + 1));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String encodeCursor(ChatMessage message) {
        return message.getCreatedAt().toString() + CURSOR_DELIMITER + message.getId();
    }
}
