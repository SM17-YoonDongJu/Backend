package com.soma.backend.domain.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NotificationSetting#allows(NotificationType)} 매핑 단위 테스트.
 * 토글 데이터를 소유한 엔티티가 type→토글 매핑을 직접 판정한다
 * (이번 슬라이스의 load-bearing 매핑은 RECEIVED_PROPOSAL↔received_proposal).
 */
@DisplayName("NotificationSetting.allows 매핑 단위 테스트")
class NotificationSettingTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Test
  @DisplayName("received_proposal 토글이 ON이면 allows(RECEIVED_PROPOSAL)은 true다")
  void allowsReceivedProposalWhenToggleOnIsTrue() {
    NotificationSetting setting = NotificationSetting.createDefault(USER_ID);

    assertThat(setting.allows(NotificationType.RECEIVED_PROPOSAL)).isTrue();
  }

  @Test
  @DisplayName("received_proposal 토글이 OFF면 allows(RECEIVED_PROPOSAL)은 false다")
  void allowsReceivedProposalWhenToggleOffIsFalse() {
    NotificationSetting setting = NotificationSetting.createDefault(USER_ID);
    setting.applyPatch(null, null, null, null, null, false, null, null, null, null);

    assertThat(setting.allows(NotificationType.RECEIVED_PROPOSAL)).isFalse();
  }

  @Test
  @DisplayName("review_complete 토글이 allows(REVIEW_COMPLETE)에 그대로 매핑된다")
  void allowsReviewCompleteMapsReviewCompleteToggle() {
    NotificationSetting on = NotificationSetting.createDefault(USER_ID);
    assertThat(on.allows(NotificationType.REVIEW_COMPLETE)).isTrue();

    NotificationSetting off = NotificationSetting.createDefault(USER_ID);
    off.applyPatch(null, null, null, null, false, null, null, null, null, null);
    assertThat(off.allows(NotificationType.REVIEW_COMPLETE)).isFalse();
  }

  @Test
  @DisplayName("settlement_notice(기본 OFF)는 fail-open이 아니라 실제 토글을 따른다")
  void allowsSettlementNoticeFollowsToggleNotFailOpen() {
    NotificationSetting setting = NotificationSetting.createDefault(USER_ID);

    assertThat(setting.allows(NotificationType.SETTLEMENT_NOTICE)).isFalse();
  }

  @Test
  @DisplayName("CHAT_MESSAGE와 CONSULT_REQUESTED는 공용 consult_message 토글로 매핑된다")
  void allowsChatAndConsultRequestedShareConsultMessageToggle() {
    NotificationSetting setting = NotificationSetting.createDefault(USER_ID);
    setting.applyPatch(null, false, null, null, null, null, null, null, null, null);

    assertThat(setting.allows(NotificationType.CHAT_MESSAGE)).isFalse();
    assertThat(setting.allows(NotificationType.CONSULT_REQUESTED)).isFalse();
  }

  @Test
  @DisplayName("전용 토글이 없는 PROPOSAL_CLOSED는 다른 토글을 모두 꺼도 fail-open(true)이다")
  void allowsProposalClosedIsFailOpenTrue() {
    NotificationSetting setting = NotificationSetting.createDefault(USER_ID);
    setting.applyPatch(false, false, false, false, false, false, false, false, false, false);

    assertThat(setting.allows(NotificationType.PROPOSAL_CLOSED)).isTrue();
  }
}
