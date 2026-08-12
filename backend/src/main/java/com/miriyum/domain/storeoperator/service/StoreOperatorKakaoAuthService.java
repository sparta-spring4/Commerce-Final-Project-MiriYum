package com.miriyum.domain.storeoperator.service;

import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.social.client.KakaoOAuthClient;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoAuthorization;
import com.miriyum.domain.auth.social.dto.KakaoLoginResult;
import com.miriyum.domain.auth.social.dto.KakaoOAuthState;
import com.miriyum.domain.auth.social.dto.KakaoOAuthUser;
import com.miriyum.domain.auth.social.dto.KakaoSignUpTicket;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.domain.auth.social.service.KakaoIdentityFingerprintGenerator;
import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import com.miriyum.domain.auth.social.service.KakaoSocialLoginLinkService;
import com.miriyum.domain.storeoperator.dto.auth.StoreOperatorKakaoSignUpRequest;
import com.miriyum.domain.storeoperator.entity.StoreOperatorAccount;
import com.miriyum.domain.storeoperator.enums.StoreOperatorAccountStatus;
import com.miriyum.domain.storeoperator.repository.StoreOperatorAccountRepository;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카카오 가입 필수 정보를 검증하고 매장 운영자 계정과 소셜 연결을 함께 생성한다. */
@Service
public class StoreOperatorKakaoAuthService {

    private final StoreOperatorAccountRepository storeOperatorAccountRepository;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthStateService kakaoOAuthStateService;
    private final KakaoIdentityFingerprintGenerator fingerprintGenerator;
    private final KakaoSignUpTicketService kakaoSignUpTicketService;
    private final KakaoSocialLoginLinkService kakaoSocialLoginLinkService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PhoneNumberPolicy phoneNumberPolicy;

    public StoreOperatorKakaoAuthService(
            StoreOperatorAccountRepository storeOperatorAccountRepository,
            KakaoOAuthClient kakaoOAuthClient,
            KakaoOAuthStateService kakaoOAuthStateService,
            KakaoIdentityFingerprintGenerator fingerprintGenerator,
            KakaoSignUpTicketService kakaoSignUpTicketService,
            KakaoSocialLoginLinkService kakaoSocialLoginLinkService,
            JwtTokenProvider jwtTokenProvider,
            PhoneNumberPolicy phoneNumberPolicy
    ) {
        this.storeOperatorAccountRepository = storeOperatorAccountRepository;
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.kakaoOAuthStateService = kakaoOAuthStateService;
        this.fingerprintGenerator = fingerprintGenerator;
        this.kakaoSignUpTicketService = kakaoSignUpTicketService;
        this.kakaoSocialLoginLinkService = kakaoSocialLoginLinkService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.phoneNumberPolicy = phoneNumberPolicy;
    }

    public String createLoginAuthorizationUrl(String redirectUri) {
        return createLoginAuthorization(redirectUri).authorizationUrl();
    }

    public KakaoAuthorization createLoginAuthorization(String redirectUri) {
        String state = kakaoOAuthStateService.createLoginState(TokenNamespace.STORE_OPERATOR);
        return new KakaoAuthorization(kakaoOAuthClient.createAuthorizationUrl(state, redirectUri), state);
    }

    public String createLinkAuthorizationUrl(Long accountId, String redirectUri) {
        return createLinkAuthorization(accountId, redirectUri).authorizationUrl();
    }

    public KakaoAuthorization createLinkAuthorization(Long accountId, String redirectUri) {
        String state = kakaoOAuthStateService.createLinkState(TokenNamespace.STORE_OPERATOR, accountId);
        return new KakaoAuthorization(kakaoOAuthClient.createAuthorizationUrl(state, redirectUri), state);
    }

    public KakaoLoginResult authenticate(KakaoAuthenticationRequest request) {
        KakaoOAuthState state = kakaoOAuthStateService.parse(request.state());
        if (state.namespace() != TokenNamespace.STORE_OPERATOR || state.purpose() != KakaoOAuthPurpose.LOGIN) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        KakaoOAuthUser user = kakaoOAuthClient.authenticate(request.authorizationCode(), request.redirectUri());
        Long accountId = kakaoSocialLoginLinkService.findLinkedAccountId(
                TokenNamespace.STORE_OPERATOR, user.providerSubject());
        if (accountId != null) {
            StoreOperatorAccount account = storeOperatorAccountRepository.findById(accountId)
                    .orElseThrow(() -> new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID));
            if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
                throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
            }
            return KakaoLoginResult.authenticated(issueTokenPair(account.getId()));
        }

        String fingerprint = fingerprintGenerator.generate(user.providerSubject());
        return KakaoLoginResult.signUpRequired(
                kakaoSignUpTicketService.create(TokenNamespace.STORE_OPERATOR, fingerprint));
    }

    @Transactional
    public KakaoLinkResult linkKakao(Long accountId, KakaoAuthenticationRequest request) {
        KakaoOAuthState state = kakaoOAuthStateService.parse(request.state());
        if (state.namespace() != TokenNamespace.STORE_OPERATOR
                || state.purpose() != KakaoOAuthPurpose.LINK
                || !accountId.equals(state.accountId())) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        StoreOperatorAccount account = storeOperatorAccountRepository.findById(accountId)
                .orElseThrow(() -> new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID));
        if (account.getStatus() != StoreOperatorAccountStatus.ACTIVE) {
            throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
        }

        KakaoOAuthUser user = kakaoOAuthClient.authenticate(request.authorizationCode(), request.redirectUri());
        return kakaoSocialLoginLinkService.link(TokenNamespace.STORE_OPERATOR, accountId, user.providerSubject());
    }

    @Transactional
    public KakaoLoginResult signUp(StoreOperatorKakaoSignUpRequest request) {
        KakaoSignUpTicket ticket = kakaoSignUpTicketService.parse(request.signUpTicket());
        if (ticket.namespace() != TokenNamespace.STORE_OPERATOR) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        String normalizedPhone = phoneNumberPolicy.normalize(request.phoneNumber());
        if (storeOperatorAccountRepository.existsByEmail(request.email())) {
            throw new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (storeOperatorAccountRepository.existsByPhone(normalizedPhone)) {
            throw new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }

        StoreOperatorAccount account = StoreOperatorAccount.createWithContact(
                request.email(), null, request.displayName(), normalizedPhone);
        try {
            StoreOperatorAccount saved = storeOperatorAccountRepository.saveAndFlush(account);
            KakaoLinkResult linkResult = kakaoSocialLoginLinkService.linkFingerprint(
                    TokenNamespace.STORE_OPERATOR, saved.getId(), ticket.providerSubjectFingerprint());
            if (linkResult != KakaoLinkResult.CREATED && linkResult != KakaoLinkResult.ALREADY_LINKED) {
                throw new ServiceException(CommonErrorCode.CONCURRENT_MODIFICATION);
            }
            return KakaoLoginResult.authenticated(issueTokenPair(saved.getId()));
        } catch (DataIntegrityViolationException exception) {
            throw mapDuplicateConstraint(exception);
        }
    }

    private TokenPair issueTokenPair(Long accountId) {
        return new TokenPair(
                jwtTokenProvider.generateAccessToken(TokenNamespace.STORE_OPERATOR, accountId),
                jwtTokenProvider.generateRefreshToken(TokenNamespace.STORE_OPERATOR, accountId));
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
}
