package com.soma.backend.domain.auth.service;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.event.UserWithdrawnEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordCredentialEraser 단위 테스트")
class PasswordCredentialEraserTest {

  @Mock
  private PasswordCredentialRepository passwordCredentialRepository;
  @InjectMocks
  private PasswordCredentialEraser eraser;

  @Test
  @DisplayName("탈퇴 이벤트의 userId로 자격증명을 삭제한다")
  void onUserWithdrawn_deletesCredential() {
    UUID userId = UUID.randomUUID();

    eraser.onUserWithdrawn(new UserWithdrawnEvent(userId));

    then(passwordCredentialRepository).should().deleteByUserId(userId);
  }

  @Test
  @DisplayName("같은 이벤트를 두 번 받아도 삭제만 두 번 호출되고 예외가 없다(멱등)")
  void onUserWithdrawn_isIdempotent() {
    UUID userId = UUID.randomUUID();
    UserWithdrawnEvent event = new UserWithdrawnEvent(userId);

    eraser.onUserWithdrawn(event);
    eraser.onUserWithdrawn(event);

    then(passwordCredentialRepository).should(times(2)).deleteByUserId(userId);
  }
}
