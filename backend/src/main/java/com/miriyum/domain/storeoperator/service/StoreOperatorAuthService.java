package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.AccountType;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매장 운영자 가입·로그인·재발급·로그아웃을 담당한다.
 *
 * <p>이메일·본인확인 참조는 제공업체가 아직 선정되지 않아({@code docs/specs/auth-account/spec.md}
 * "공급자 중립 확인 참조" 절) 실제 서버 대 서버 검증을 연결하지 못한다. 정본 명세는 어댑터가 없으면
 * 확인을 우회한 운영 계정을 만들지 않도록 정하므로, {@code miriyum.identity-verification.dev-stub-enabled}가
 * 꺼져 있으면(기본값) 가입 자체를 차단한다. 이 값이 켜진 개발 환경에서만 공백 검사 스텁으로 가입을
 * 허용한다.</p>
 *
 * <p>{@code identityVerificationReference}는 불투명 일회성 참조일 뿐 전화번호가 아니므로,
 * 실제 전화번호로 해석해주는 어댑터가 생기기 전까지 계정의 {@code phone}은 채우지 않고 BLOCKED로
 * 남겨둔다(비어 있음).</p>
 */
@Service
public class StoreOperatorAuthService {

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final boolean identityVerificationDevStubEnabled;

    public StoreOperatorAuthService(
            StoreOperatorAccountRepository storeOperatorAccountRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${miriyum.identity-verification.dev-stub-enabled}") boolean identityVerificationDevStubEnabled
    ) {
        this.storeOperatorAccountRepository = storeOperatorAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.identityVerificationDevStubEnabled = identityVerificationDevStubEnabled;
    }

    @Transactional
    public AccountCreatedResponse signUp(StoreOperatorSignUpRequest request) {
        if (!identityVerificationDevStubEnabled) {
            throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        if (!request.password().equals(request.passwordConfirm())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (storeOperatorAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        StoreOperatorAccount account =
                StoreOperatorAccount.create(request.email(), passwordHash, request.displayName());

        StoreOperatorAccount saved;
        try {
            saved = storeOperatorAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            throw mapDuplicateConstraint(exception);
        }

        return AccountCreatedResponse.of(saved.getId(), AccountType.STORE_OPERATOR);
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_store_operator_accounts_email")) {
            return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
    }

    @Transactional(readOnly = true)
    public TokenPair login(LoginRequest request) {
        StoreOperatorAccount account = storeOperatorAccountRepository.findByEmail(request.email())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new ServiceException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
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
        if (parsed.namespace() != TokenNamespace.STORE_OPERATOR) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }

        StoreOperatorAccount account = storeOperatorAccountRepository.findById(parsed.accountId())
                .orElseThrow(() -> new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }

        return issueTokenPair(account.getId());
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_REQUIRED);
        }

        ParsedToken parsed = jwtTokenProvider.parseRefreshToken(refreshToken);
        if (parsed.namespace() != TokenNamespace.STORE_OPERATOR) {
            throw new ServiceException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 1차 MVP는 중앙 토큰 상태가 없으므로 서버 폐기 상태를 별도로 기록하지 않는다.
    }

    private TokenPair issueTokenPair(Long accountId) {
        String accessToken = jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(TokenNamespace.STORE_OPERATOR, accountId);
        return new TokenPair(accessToken, refreshToken);
    }
}
