package com.soma.backend.global.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드 파라미터에 현재 "활성 손해사정사"의 userId(UUID)를 주입한다.
 *
 * <p>주입 과정에서 다음 인가 가드를 수행한다.
 * <ul>
 *   <li>비로그인(principal == null) → {@code BusinessException(LOGIN_REQUIRED)} → 401</li>
 *   <li>로그인했으나 role != CERTIFICATED_ADJUSTER → {@code BusinessException(FORBIDDEN)} → 403</li>
 *   <li>role == CERTIFICATED_ADJUSTER → 통과, 파라미터에 UUID 주입</li>
 * </ul>
 *
 * <p>대상 파라미터 타입은 반드시 {@link java.util.UUID} 여야 한다.
 *
 * <pre>{@code
 * @GetMapping("/reports/pending-review/summary")
 * public ResponseEntity<ApiResponse<...>> summary(@ActiveAdjuster UUID adjusterId) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActiveAdjuster {
}
