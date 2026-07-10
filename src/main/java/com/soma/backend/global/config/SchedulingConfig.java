package com.soma.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 활성화. 아웃박스 poller 등 주기 작업 실행에 필요하다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
