package com.soma.backend.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드 파라미터에 현재 로그인한 사용자의 userId(UUID)를 주입한다.
 *
 * <p>{@link ActiveAdjuster}와 달리 역할(role) 제한 없이 로그인 여부만 검사한다.
 *
 * <p>주입 과정에서 다음 인가 가드를 수행한다.
 * <ul>
 *   <li>비로그인(principal == null) → {@code BusinessException(LOGIN_REQUIRED)} → 401</li>
 *   <li>로그인 상태(principal이 {@link CustomUserDetails}) → 통과, 파라미터에 UUID 주입</li>
 * </ul>
 *
 * <p>대상 파라미터 타입은 반드시 {@link java.util.UUID} 여야 한다.
 *
 * <pre>{@code
 * @PostMapping("/reports")
 * public ResponseEntity<ApiResponse<...>> create(@CurrentUserId UUID userId, ...) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
