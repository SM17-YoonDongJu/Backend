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
import com.soma.backend.infra.redis.WithdrawalLedgerRepository;

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
  private final WithdrawalLedgerRepository withdrawalLedgerRepository;

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
    if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    Role role = UserType.from(request.userType()).toRole();

    User user = User.create(
        request.nickname(), request.birthDate(), request.gender(), request.phoneNumber(), role);
    userRepository.save(user);

    SocialAccount account = SocialAccount.create(
        user.getId(), ticket.provider(), ticket.providerUserId());
    socialAccountRepository.save(account);

    // 재가입이 완료됐으므로 탈퇴 원장에서 이 소셜 신원을 제거한다(더는 재가입 대상이 아님).
    withdrawalLedgerRepository.clear(ticket.provider(), ticket.providerUserId());

    authTokenService.issueTokens(response, user.getId(), role.name());
    return new RegisterResponse(user.getId(), user.getNickname(), role.name());
  }
}
