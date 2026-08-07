package com.soma.backend.domain.notification.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.notification.entity.Notification;

/**
 * 인앱 알림 목록 응답(items + 미읽음 수 + page 메타). 필드는 Jackson 전역 설정에 따라 snake_case로
 * 직렬화된다(unread_count, total_elements, total_pages, is_read, created_at).
 */
public record NotificationListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unreadCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalElements,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalPages) {

  public static NotificationListResponse from(Page<Notification> page, long unreadCount) {
    List<Item> items = page.getContent().stream().map(Item::from).toList();
    return new NotificationListResponse(
        items, unreadCount, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** 알림 목록 아이템 1건({id, type, title, body, is_read, created_at}). */
  public record Item(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
      @Nullable @Schema(nullable = true) String body,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isRead,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {

    public static Item from(Notification notification) {
      return new Item(
          notification.getId(),
          notification.getType().name(),
          notification.getTitle(),
          notification.getBody(),
          notification.isRead(),
          notification.getCreatedAt());
    }
  }
}
