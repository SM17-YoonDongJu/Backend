package com.soma.backend.domain.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("JpaConfig 단위 테스트")
class JpaConfigTest {

    @Test
    @DisplayName("JpaConfig에 @Configuration 어노테이션이 있어야 한다")
    void jpaConfig_hasConfigurationAnnotation() {
        assertNotNull(JpaConfig.class.getAnnotation(Configuration.class),
            "JpaConfig must be annotated with @Configuration");
    }

    @Test
    @DisplayName("JpaConfig에 @EnableJpaAuditing 어노테이션이 있어야 한다")
    void jpaConfig_hasEnableJpaAuditingAnnotation() {
        assertNotNull(JpaConfig.class.getAnnotation(EnableJpaAuditing.class),
            "JpaConfig must be annotated with @EnableJpaAuditing to activate JPA auditing");
    }

    @Test
    @DisplayName("JpaConfig 인스턴스를 생성할 수 있어야 한다")
    void jpaConfig_canBeInstantiated() {
        assertDoesNotThrow(JpaConfig::new,
            "JpaConfig must be instantiable (no-arg constructor required by Spring)");
    }

    @Test
    @DisplayName("JpaConfig는 인터페이스나 추상 클래스가 아닌 구체 클래스여야 한다")
    void jpaConfig_isConcreteClass() {
        assertNotNull(assertDoesNotThrow(() -> JpaConfig.class.getDeclaredConstructor()),
            "JpaConfig must have a no-arg constructor accessible by Spring");
    }
}