package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.logindelay.LoginDelayGuard;
import com.miriyum.domain.auth.logindelay.LoginAttempt;
import com.miriyum.domain.auth.password.PasswordPolicy;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenManager;
import com.miriyum.domain.auth.refreshtoken.RefreshTokenRotationAttempt;
import com.miriyum.domain.consumer.dto.auth.ConsumerSignUpRequest;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsumerAuthService {

    private final ConsumerAccountRepository consumerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final NicknamePolicy nicknamePolicy;
    private final PasswordPolicy passwordPolicy;
    private final LoginDelayGuard loginDelayGuard;
    private final PhoneNumberPolicy phoneNumberPolicy;
    private final ReservationContactReferenceGenerator contactReferenceGenerator;
    private final RefreshTokenManager refreshTokenManager;

    public ConsumerAuthService(
            ConsumerAccountRepository consumerAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            NicknamePolicy nicknamePolicy,
            PasswordPolicy passwordPolicy,
            LoginDelayGuard loginDelayGuard,
            PhoneNumberPolicy phoneNumberPolicy,
            ReservationContactReferenceGenerator contactReferenceGenerator,
            RefreshTokenManager refreshTokenManager
    ) {
        this.consumerAccountRepository = consumerAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.nicknamePolicy = nicknamePolicy;
        this.passwordPolicy = passwordPolicy;
        this.loginDelayGuard = loginDelayGuard;
        this.phoneNumberPolicy = phoneNumberPolicy;
        this.contactReferenceGenerator = contactReferenceGenerator;
        this.refreshTokenManager = refreshTokenManager;
    }

    @Transactional
    public AccountCreatedResponse signUp(ConsumerSignUpRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!request.ageConfirmed()) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        String normalizedPhone = phoneNumberPolicy.normalize(request.phoneNumber());
        if (consumerAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (consumerAccountRepository.existsByPhone(normalizedPhone)) {
            throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }

        String normalizedNickname = nicknamePolicy.normalize(request.nickname());
        String normalizedPassword = passwordPolicy.normalize(request.password());
        ConsumerAccount account = ConsumerAccount.createWithContact(
                request.email(),
                passwordEncoder.encode(normalizedPassword),
                normalizedNickname,
                normalizedPhone,
                contactReferenceGenerator.generate());

        try {
            ConsumerAccount saved = consumerAccountRepository.saveAndFlush(account);
            return AccountCreatedResponse.of(saved.getId(), AccountType.CONSUMER);
        } catch (DataIntegrityViolationException exception) {
            throw mapDuplicateConstraint(exception);
        }
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_consumer_accounts_email")) {
            return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (message != null && message.contains("uk_consumer_accounts_phone")) {
            return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    public TokenPair login(LoginRequest request) {
        ConsumerAccount account = consumerAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));

        LoginAttempt attempt = loginDelayGuard.tryAcquireAttempt(TokenNamespace.CONSUMER, account.getId());
        if (attempt.status() == LoginAttempt.Status.DELAYED) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (attempt.status() == LoginAttempt.Status.BUSY) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        boolean completed = false;
        try {
            boolean passwordMatches = matchesPassword(
                    passwordPolicy.toNfc(request.password()), account.getPasswordHash());
            boolean attemptCompleted = loginDelayGuard.completeAttempt(
                    TokenNamespace.CONSUMER, account.getId(), attempt, passwordMatches);
            completed = true;
            if (!attemptCompleted || !passwordMatches) {
                throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
            }

            long sessionEpoch = refreshTokenManager.captureSessionEpoch(TokenNamespace.CONSUMER, account.getId());
            ConsumerAccount currentAccount = consumerAccountRepository.findById(account.getId())
                    .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));
            if (currentAccount.getStatus() != ConsumerAccountStatus.ACTIVE) {
                throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
            }
            return issueTokenPair(currentAccount.getId(), sessionEpoch);
        } finally {
            if (!completed) {
                loginDelayGuard.releaseAttempt(TokenNamespace.CONSUMER, account.getId(), attempt);
            }
        }
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
        RefreshTokenRotationAttempt attempt = refreshTokenManager.attemptRotate(
                TokenNamespace.CONSUMER, parsed, refreshToken);
        if (attempt.reused()) {
            refreshTokenManager.revokeAll(TokenNamespace.CONSUMER, account.getId());
        }
        if (!attempt.rotated()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        TokenPair tokenPair = attempt.tokenPair();
        if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
            refreshTokenManager.revokeAll(TokenNamespace.CONSUMER, account.getId());
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }

        return tokenPair;
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshTokenForLogout(refreshToken);
        if (parsed == null || parsed.namespace() != TokenNamespace.CONSUMER) {
            return;
        }
        refreshTokenManager.revoke(TokenNamespace.CONSUMER, parsed);
    }

    private TokenPair issueTokenPair(Long accountId, long sessionEpoch) {
        return refreshTokenManager.issue(TokenNamespace.CONSUMER, accountId, sessionEpoch);
    }

    private boolean matchesPassword(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
