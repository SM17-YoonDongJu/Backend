package com.soma.backend.domain.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.RegisterRequest;
import com.soma.backend.domain.auth.dto.RegisterResponse;
import com.soma.backend.domain.auth.entity.SocialAccount;
import com.soma.backend.domain.auth.entity.UserType;
import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;

/**
 * 소셜 회원가입 유스케이스. 가입 티켓을 검증하고 users + social_accounts를 한 트랜잭션으로 생성한 뒤
 * access·refresh 쿠키를 발급한다.
 */
@Service
@RequiredArgsConstructor
public class AuthRegisterService {

  private final SignupTicketProvider signupTicketProvider;
  private final UserRepository userRepository;
  private final SocialAccountRepository socialAccountRepository;
  private final AuthTokenService authTokenService;

  @Transactional
  public RegisterResponse register(HttpServletResponse response, RegisterRequest request) {
    SignupTicket ticket = signupTicketProvider.parse(request.socialToken());
    if (!ticket.provider().equals(request.provider())) {
      throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }

    if (socialAccountRepository
        .existsByProviderAndProviderUserId(ticket.provider(), ticket.providerUserId())) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    if (userRepository.existsByNickname(request.nickname())) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    Role role = UserType.from(request.userType()).toRole();
    String email = ticket.email() != null ? ticket.email() : request.email();

    User user = User.create(request.nickname(), email, role);
    userRepository.save(user);

    SocialAccount account = SocialAccount.create(
        user.getId(), ticket.provider(), ticket.providerUserId());
    socialAccountRepository.save(account);

    authTokenService.issueTokens(response, user.getId(), role.name());
    return new RegisterResponse(user.getId(), user.getNickname(), role.name());
  }
}
