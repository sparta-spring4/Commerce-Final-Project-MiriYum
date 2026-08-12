package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
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
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreOperatorAuthService {

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordPolicy passwordPolicy;
    private final LoginDelayGuard loginDelayGuard;
    private final PhoneNumberPolicy phoneNumberPolicy;
    private final RefreshTokenManager refreshTokenManager;

    public StoreOperatorAuthService(
            StoreOperatorAccountRepository storeOperatorAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            PasswordPolicy passwordPolicy,
            LoginDelayGuard loginDelayGuard,
            PhoneNumberPolicy phoneNumberPolicy,
            RefreshTokenManager refreshTokenManager
    ) {
        this.storeOperatorAccountRepository = storeOperatorAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordPolicy = passwordPolicy;
        this.loginDelayGuard = loginDelayGuard;
        this.phoneNumberPolicy = phoneNumberPolicy;
        this.refreshTokenManager = refreshTokenManager;
    }

    @Transactional
    public AccountCreatedResponse signUp(StoreOperatorSignUpRequest request) {
        if (!request.password().equals(request.passwordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        String normalizedPhone = phoneNumberPolicy.normalize(request.phoneNumber());
        if (storeOperatorAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (storeOperatorAccountRepository.existsByPhone(normalizedPhone)) {
            throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }

        String normalizedPassword = passwordPolicy.normalize(request.password());
        StoreOperatorAccount account = StoreOperatorAccount.createWithContact(
                request.email(),
                passwordEncoder.encode(normalizedPassword),
                request.displayName(),
                normalizedPhone);

        try {
            StoreOperatorAccount saved = storeOperatorAccountRepository.saveAndFlush(account);
            return AccountCreatedResponse.of(saved.getId(), AccountType.STORE_OPERATOR);
        } catch (DataIntegrityViolationException exception) {
            throw mapDuplicateConstraint(exception);
        }
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_store_operator_accounts_email")) {
            return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (message != null && message.contains("uk_store_operator_accounts_phone")) {
            return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    public TokenPair login(LoginRequest request) {
        StoreOperatorAccount account = storeOperatorAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));

        LoginAttempt attempt = loginDelayGuard.tryAcquireAttempt(TokenNamespace.STORE_OPERATOR, account.getId());
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
                    TokenNamespace.STORE_OPERATOR, account.getId(), attempt, passwordMatches);
            completed = true;
            if (!attemptCompleted || !passwordMatches) {
                throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
            }

            long sessionEpoch = refreshTokenManager.captureSessionEpoch(TokenNamespace.STORE_OPERATOR, account.getId());
            StoreOperatorAccount currentAccount = storeOperatorAccountRepository.findById(account.getId())
                    .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));
            if (currentAccount.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
                throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
            }
            return issueTokenPair(currentAccount.getId(), sessionEpoch);
        } finally {
            if (!completed) {
                loginDelayGuard.releaseAttempt(TokenNamespace.STORE_OPERATOR, account.getId(), attempt);
            }
        }
    }

    @Transactional(readOnly = true)
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshToken(refreshToken);
        if (parsed.namespace() != TokenNamespace.STORE_OPERATOR) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        StoreOperatorAccount account = storeOperatorAccountRepository.findById(parsed.accountId())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            refreshTokenManager.revokeAll(TokenNamespace.STORE_OPERATOR, account.getId());
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }

        return refreshTokenManager.rotate(TokenNamespace.STORE_OPERATOR, parsed, refreshToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshTokenForLogout(refreshToken);
        if (parsed == null || parsed.namespace() != TokenNamespace.STORE_OPERATOR) {
            return;
        }
        refreshTokenManager.revoke(TokenNamespace.STORE_OPERATOR, parsed);
    }

    private TokenPair issueTokenPair(Long accountId, long sessionEpoch) {
        return refreshTokenManager.issue(TokenNamespace.STORE_OPERATOR, accountId, sessionEpoch);
    }

    private boolean matchesPassword(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
