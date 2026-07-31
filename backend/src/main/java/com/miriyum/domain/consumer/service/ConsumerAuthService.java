package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.consumer.dto.request.ConsumerSignUpRequest;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 사용자 가입·로그인·재발급·로그아웃을 담당한다.
 *
 * <p>이메일·본인확인 참조는 제공업체가 아직 선정되지 않아({@code docs/specs/auth-account/spec.md}
 * "공급자 중립 확인 참조" 절) 실제 서버 대 서버 검증을 연결하지 못한다. 정본 명세는 어댑터가 없으면
 * 확인을 우회한 운영 계정을 만들지 않도록 정하므로, {@code miriyum.identity-verification.dev-stub-enabled}가
 * 꺼져 있으면(기본값) 가입 자체를 차단한다. 이 값이 켜진 개발 환경에서만 공백 검사 스텁으로 가입을
 * 허용한다.</p>
 *
 * <p>{@code identityVerificationReference}는 불투명 일회성 참조일 뿐 전화번호가 아니므로,
 * 실제 전화번호로 해석해주는 어댑터가 생기기 전까지 계정의 {@code phone}은 채우지 않고 BLOCKED로
 * 남겨둔다(비어 있음). 참조값을 전화번호 자리에 대신 저장하거나 응답으로 노출하지 않는다.</p>
 */
@Service
public class ConsumerAuthService {

    private final ConsumerAccountRepository consumerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final NicknamePolicy nicknamePolicy;
    private final PasswordPolicy passwordPolicy;
    private final LoginDelayGuard loginDelayGuard;
    private final boolean identityVerificationDevStubEnabled;

    public ConsumerAuthService(
            ConsumerAccountRepository consumerAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            NicknamePolicy nicknamePolicy,
            PasswordPolicy passwordPolicy,
            LoginDelayGuard loginDelayGuard,
            @Value("${miriyum.identity-verification.dev-stub-enabled}") boolean identityVerificationDevStubEnabled
    ) {
        this.consumerAccountRepository = consumerAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.nicknamePolicy = nicknamePolicy;
        this.passwordPolicy = passwordPolicy;
        this.loginDelayGuard = loginDelayGuard;
        this.identityVerificationDevStubEnabled = identityVerificationDevStubEnabled;
    }

    @Transactional
    public AccountCreatedResponse signUp(ConsumerSignUpRequest request) {
        if (!identityVerificationDevStubEnabled) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!request.password().equals(request.passwordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (consumerAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String normalizedNickname = nicknamePolicy.normalize(request.nickname());
        String normalizedPassword = passwordPolicy.normalize(request.password());
        String passwordHash = passwordEncoder.encode(normalizedPassword);
        ConsumerAccount account = ConsumerAccount.create(request.email(), passwordHash, normalizedNickname);

        ConsumerAccount saved;
        try {
            saved = consumerAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            throw mapDuplicateConstraint(exception);
        }

        return AccountCreatedResponse.of(saved.getId(), AccountType.CONSUMER);
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_consumer_accounts_email")) {
            return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    /**
     * 이메일·비밀번호 로그인이다. AUTH-006의 계정 단위 지연을 적용한다.
     *
     * <p>{@link LoginDelayGuard#tryReserveAttempt}가 계정 행을 잠근 채 지연 여부를 판정하고 실패를
     * 미리 기록한다. 지연 중이면 비밀번호 비교 자체를 수행하지 않으며, 응답은 평소 실패와 같은
     * {@code AUTH_005}다. 실패 횟수·지연 상태·계정 존재 여부를 응답으로 구분할 수 없어야 한다.
     * 비밀번호가 맞으면 예약한 실패를 {@link LoginDelayGuard#reset}으로 되돌린다.</p>
     *
     * <p>이 메서드에는 일부러 트랜잭션 경계를 두지 않는다. 조회 → 판정 → 짧은 기록 순서라 전체를
     * 하나로 묶어야 하는 불변식이 없고, 원자성이 필요한 실패 카운터 증가는 {@link LoginDelayGuard}가
     * 자체 트랜잭션과 행 잠금으로 이미 보장한다. 반대로 여기에 트랜잭션을 걸면 실패 기록용
     * {@code REQUIRES_NEW} 트랜잭션이 커넥션을 하나 더 잡아 요청당 두 개를 쓰게 되고, 동시 로그인
     * 실패가 몰릴 때 커넥션 풀이 고갈된다.</p>
     */
    public TokenPair login(LoginRequest request) {
        ConsumerAccount account = consumerAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!loginDelayGuard.tryReserveAttempt(TokenNamespace.CONSUMER, account.getId())) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        // 해시 비교는 트랜잭션 밖에서 한다. 위 예약이 이미 커밋됐으므로 실패는 기록된 상태다.
        if (!passwordEncoder.matches(passwordPolicy.toNfc(request.password()), account.getPasswordHash())) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        loginDelayGuard.reset(TokenNamespace.CONSUMER, account.getId());

        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }
        return issueTokenPair(account.getId());
    }

    @Transactional(readOnly = true)
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshToken(refreshToken);
        if (parsed.namespace() != TokenNamespace.CONSUMER) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        ConsumerAccount account = consumerAccountRepository.findById(parsed.accountId())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }

        return issueTokenPair(account.getId());
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshToken(refreshToken);
        if (parsed.namespace() != TokenNamespace.CONSUMER) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 1차 MVP는 중앙 토큰 상태가 없으므로 서버 폐기 상태를 별도로 기록하지 않는다.
    }

    private TokenPair issueTokenPair(Long accountId) {
        String accessToken = jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, accountId);
        return new TokenPair(accessToken, refreshToken);
    }
}
