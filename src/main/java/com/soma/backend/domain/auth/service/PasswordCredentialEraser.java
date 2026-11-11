package com.soma.backend.domain.auth.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.event.UserWithdrawnEvent;

/**
 * 탈퇴한 회원의 아이디·비밀번호 자격증명을 파기하는 리스너.
 *
 * <p>자격증명({@code password_credentials})은 auth 컨텍스트 Aggregate라서, 탈퇴를 수행하는 user 컨텍스트가
 * 이 테이블을 직접 지우지 않는다 — user는 {@link UserWithdrawnEvent}만 발행하고 삭제 방법은 여기서 정한다.
 *
 * <p>지우는 이유는 두 가지다. 비밀번호 해시를 남겨 두면 탈퇴 시 개인정보를 파기한다는 정책과 어긋나고,
 * {@code login_id} UNIQUE가 영구 점유돼 같은 사람이 같은 아이디로 다시 가입할 수 없다 — 탈퇴 시 전화번호
 * UNIQUE를 풀어 동일 번호 재가입을 허용하는 {@code User.withdraw} 규약과 대칭을 맞춘다.
 *
 * <p>소셜로만 가입한 회원은 자격증명 행이 없어 삭제 대상이 0건이고, 그래도 아무 일도 하지 않으므로
 * 멱등하다(탈퇴 재시도·이벤트 중복에도 안전).
 *
 * <p>리스너는 {@code @EventListener}(동기)이고 기본 전파(REQUIRED)라 발행자(탈퇴)의 트랜잭션에 합류한다.
 * 예외도 발행자에게 그대로 전파된다 — 자격증명을 못 지웠는데 탈퇴만 커밋되는 상태를 만들지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PasswordCredentialEraser {

  private final PasswordCredentialRepository passwordCredentialRepository;

  /** 탈퇴 이벤트 → 해당 회원의 자격증명 삭제(없으면 no-op). */
  @EventListener
  @Transactional
  public void onUserWithdrawn(UserWithdrawnEvent event) {
    passwordCredentialRepository.deleteByUserId(event.userId());
  }
}
