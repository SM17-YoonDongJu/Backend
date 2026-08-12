package com.soma.backend.domain.auth.service;

import java.util.List;

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
import com.soma.backend.global.common.RegionFormat;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiHmac;
import com.soma.backend.infra.redis.AppleRefreshStagingRepository;

/**
 * 소셜 회원가입 유스케이스. 가입 티켓을 검증하고 users + social_accounts를 한 트랜잭션으로 생성한 뒤
 * access·refresh 쿠키를 발급한다.
 *
 * <p>Apple 가입이면 콜백에서 스테이징해 둔 refresh_token(암호문)을 소비해 SocialAccount에 옮긴다
 * (탈퇴 revoke용). 스테이징 값은 이미 암호문이라 추가 암호화 없이 그대로 저장한다.
 *
 * <p>phone_number·provider_user_id는 암호화 컬럼이라 중복확인·엔티티 생성 전에 HMAC 블라인드 인덱스를
 * 먼저 계산한다(이슈 #232) — 엔티티는 크립토 컴포넌트를 참조하지 못해 서비스가 계산해서 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class AuthRegisterService {

  private static final PiiAad PHONE_NUMBER_AAD = PiiAad.ofColumn("users", "phone_number");
  private static final PiiAad PROVIDER_USER_ID_AAD = PiiAad.ofColumn("social_accounts", "provider_user_id");

  private final SignupTicketProvider signupTicketProvider;
  private final UserRepository userRepository;
  private final SocialAccountRepository socialAccountRepository;
  private final AuthTokenService authTokenService;
  private final AppleRefreshStagingRepository appleRefreshStagingRepository;
  private final PiiHmac piiHmac;

  @Transactional
  public RegisterResponse register(HttpServletResponse response, RegisterRequest request) {
    SignupTicket ticket = signupTicketProvider.parse(request.socialToken());
    if (!ticket.provider().equals(request.provider())) {
      throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }

    byte[] providerUserIdHmac = piiHmac.hmac(ticket.providerUserId(), PROVIDER_USER_ID_AAD);
    if (socialAccountRepository
        .existsByProviderAndProviderUserIdHmac(ticket.provider(), providerUserIdHmac)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    byte[] phoneNumberHmac = piiHmac.hmac(request.phoneNumber(), PHONE_NUMBER_AAD);
    if (userRepository.existsByPhoneNumberHmac(phoneNumberHmac)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    Role role = UserType.from(request.userType()).toRole();

    // 지역은 프론트 계약상 "서울·경기" 형태의 단일 문자열이라 저장용 text[]로 되돌린다.
    // 미입력이면 빈 리스트가 되고, User.create가 이를 null로 정규화한다(빈 배열을 만들지 않는다).
    List<String> region = RegionFormat.toList(request.region());

    User user = User.create(
        request.name(), request.birthDate(), request.gender(), request.phoneNumber(), phoneNumberHmac,
        role, region);
    userRepository.save(user);

    SocialAccount account = SocialAccount.create(
        user.getId(), ticket.provider(), ticket.providerUserId(), providerUserIdHmac);
    appleRefreshStagingRepository.consume(ticket.provider(), ticket.providerUserId())
        .ifPresent(account::linkRefreshToken);
    socialAccountRepository.save(account);

    authTokenService.issueTokens(response, user.getId(), role.name());
    return new RegisterResponse(user.getId(), user.getNickname(), role.name());
  }
}
