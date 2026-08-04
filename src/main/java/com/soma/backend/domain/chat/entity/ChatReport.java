package com.soma.backend.domain.chat.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.util.StringUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅방 신고(Aggregate Root, 불변·append-only). 접수 후 상태를 바꾸는 메서드를 제공하지 않는다.
 *
 * <p>ChatRoom과는 별도 Aggregate이며 참조는 객체가 아니라 id(UUID)로만 갖는다. {@code reportedId}는
 * 접수 시점의 상대방을 고정 기록한 값으로, 서비스가 {@code ChatRoom.counterpartOf(reporterId)}로 계산해
 * 넘긴다(엔티티는 ChatRoom을 알지 못한다). 중복 신고를 허용하므로 신고자·방 조합에 대한 제약은 두지 않는다.
 */
@Entity
@Table(name = "chatroom_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatReport {

  private static final int REASON_DETAIL_MAX = 500;

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "chat_room_id", nullable = false)
  private UUID chatRoomId;

  @Column(name = "reporter_id", nullable = false)
  private UUID reporterId;

  @Column(name = "reported_id", nullable = false)
  private UUID reportedId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 30)
  private ChatReportReason reason;

  @Column(name = "reason_detail", columnDefinition = "text")
  private String reasonDetail;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private ChatReport(UUID chatRoomId, UUID reporterId, UUID reportedId, ChatReportReason reason,
      String reasonDetail) {
    this.chatRoomId = chatRoomId;
    this.reporterId = reporterId;
    this.reportedId = reportedId;
    this.reason = reason;
    this.reasonDetail = reasonDetail;
  }

  /**
   * 신고를 접수한다. reason이 OTHER면 상세 텍스트가 필수이며(MISSING_REQUIRED_FIELD),
   * 상세 텍스트는 trim 후 500자를 넘을 수 없다(VALIDATION_ERROR). 공백만이면 null로 정규화한다.
   */
  public static ChatReport create(UUID chatRoomId, UUID reporterId, UUID reportedId,
      ChatReportReason reason, String reasonDetail) {
    String normalized = normalizeDetail(reasonDetail);
    if (reason == ChatReportReason.OTHER && normalized == null) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if (normalized != null && normalized.length() > REASON_DETAIL_MAX) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    return new ChatReport(chatRoomId, reporterId, reportedId, reason, normalized);
  }

  private static String normalizeDetail(String reasonDetail) {
    return StringUtils.hasText(reasonDetail) ? reasonDetail.trim() : null;
  }
}
