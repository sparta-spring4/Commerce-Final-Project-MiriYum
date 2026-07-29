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
import com.miriyum.domain.consumer.dto.request.ConsumerSignUpRequest;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일반 사용자 가입·로그인·재발급·로그아웃을 담당한다.
 *
 * <p>이메일·본인확인 참조는 제공업체가 아직 선정되지 않아({@code docs/specs/auth-account/spec.md}
 * "공급자 중립 확인 참조" 절) 실제 서버 대 서버 검증을 연결하지 못한다. 이번 구현은 개발용으로
 * Bean Validation의 공백 검사만 통과하면 유효한 것으로 간주하는 임시 처리이며, 실제 제공업체가
 * 선정되면 참조를 해석·검증하는 어댑터로 교체해야 한다.</p>
 */
@Service
@RequiredArgsConstructor
public class ConsumerAuthService {

    private final ConsumerAccountRepository consumerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final NicknamePolicy nicknamePolicy;

    @Transactional
    public AccountCreatedResponse signUp(ConsumerSignUpRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (consumerAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (consumerAccountRepository.existsByPhone(request.identityVerificationReference())) {
            throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }

        String normalizedNickname = nicknamePolicy.normalize(request.nickname());
        String passwordHash = passwordEncoder.encode(request.password());
        ConsumerAccount account = ConsumerAccount.create(
                request.email(), passwordHash, request.identityVerificationReference(), normalizedNickname);

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
        if (message != null) {
            if (message.contains("uk_consumer_accounts_email")) {
                return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
            }
            if (message.contains("uk_consumer_accounts_phone")) {
                return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
            }
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    @Transactional(readOnly = true)
    public TokenPair login(LoginRequest request) {
        ConsumerAccount account = consumerAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
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
