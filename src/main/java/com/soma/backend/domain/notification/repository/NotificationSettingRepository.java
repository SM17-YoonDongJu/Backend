package com.soma.backend.domain.notification.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.notification.entity.NotificationSetting;

/** 알림 설정 리포지토리(USERS 1:1, user_id가 PK). */
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, UUID> {
}
