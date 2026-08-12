package com.miriyum.domain.consumer.service;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.social.client.KakaoOAuthClient;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoAuthorization;
import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
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
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.contact.ReservationContactReferenceGenerator;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.auth.ConsumerKakaoSignUpRequest;
import com.miriyum.domain.consumer.repository.ConsumerAccountRepository;
import com.miriyum.domain.consumer.entity.ConsumerAccount;
import com.miriyum.domain.consumer.enums.ConsumerAccountStatus;
import com.miriyum.global.exception.CommonErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import com.miriyum.global.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 일반 사용자 카카오 인증을 기존 연결 로그인 또는 카카오 가입 준비 단계로 분기한다. */
@Service
public class ConsumerKakaoAuthService {

    private final ConsumerAccountRepository consumerAccountRepository;
    private final ConsumerKakaoLinkTransactionService consumerKakaoLinkTransactionService;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthStateService kakaoOAuthStateService;
    private final KakaoSocialLoginLinkService kakaoSocialLoginLinkService;
    private final KakaoIdentityFingerprintGenerator fingerprintGenerator;
    private final KakaoSignUpTicketService kakaoSignUpTicketService;
    private final JwtTokenProvider jwtTokenProvider;
    private final NicknamePolicy nicknamePolicy;
    private final PhoneNumberPolicy phoneNumberPolicy;
    private final ReservationContactReferenceGenerator contactReferenceGenerator;

    public ConsumerKakaoAuthService(
            ConsumerAccountRepository consumerAccountRepository,
            ConsumerKakaoLinkTransactionService consumerKakaoLinkTransactionService,
            KakaoOAuthClient kakaoOAuthClient,
            KakaoOAuthStateService kakaoOAuthStateService,
            KakaoSocialLoginLinkService kakaoSocialLoginLinkService,
            KakaoIdentityFingerprintGenerator fingerprintGenerator,
            KakaoSignUpTicketService kakaoSignUpTicketService,
            JwtTokenProvider jwtTokenProvider,
            NicknamePolicy nicknamePolicy,
            PhoneNumberPolicy phoneNumberPolicy,
            ReservationContactReferenceGenerator contactReferenceGenerator
    ) {
        this.consumerAccountRepository = consumerAccountRepository;
        this.consumerKakaoLinkTransactionService = consumerKakaoLinkTransactionService;
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.kakaoOAuthStateService = kakaoOAuthStateService;
        this.kakaoSocialLoginLinkService = kakaoSocialLoginLinkService;
        this.fingerprintGenerator = fingerprintGenerator;
        this.kakaoSignUpTicketService = kakaoSignUpTicketService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.nicknamePolicy = nicknamePolicy;
        this.phoneNumberPolicy = phoneNumberPolicy;
        this.contactReferenceGenerator = contactReferenceGenerator;
    }

    public String createLoginAuthorizationUrl(String redirectUri) {
        return createLoginAuthorization(redirectUri).authorizationUrl();
    }

    public KakaoAuthorization createLoginAuthorization(String redirectUri) {
        String state = kakaoOAuthStateService.createLoginState(TokenNamespace.CONSUMER);
        return new KakaoAuthorization(kakaoOAuthClient.createAuthorizationUrl(state, redirectUri), state);
    }

    public String createLinkAuthorizationUrl(Long accountId, String redirectUri) {
        return createLinkAuthorization(accountId, redirectUri).authorizationUrl();
    }

    public KakaoAuthorization createLinkAuthorization(Long accountId, String redirectUri) {
        String state = kakaoOAuthStateService.createLinkState(TokenNamespace.CONSUMER, accountId);
        return new KakaoAuthorization(kakaoOAuthClient.createAuthorizationUrl(state, redirectUri), state);
    }

    public KakaoLoginResult authenticate(KakaoAuthenticationRequest request) {
        KakaoOAuthState state = kakaoOAuthStateService.parse(request.state());
        if (state.namespace() != TokenNamespace.CONSUMER || state.purpose() != KakaoOAuthPurpose.LOGIN) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        KakaoOAuthUser user = kakaoOAuthClient.authenticate(request.authorizationCode(), request.redirectUri());
        Long accountId = kakaoSocialLoginLinkService.findLinkedAccountId(
                TokenNamespace.CONSUMER, user.providerSubject());
        if (accountId != null) {
            ConsumerAccount account = consumerAccountRepository.findById(accountId)
                    .orElseThrow(() -> new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID));
            if (account.getStatus() != ConsumerAccountStatus.ACTIVE) {
                throw new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED);
            }
            TokenPair tokenPair = new TokenPair(
                    jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, account.getId()),
                    jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, account.getId()));
            return KakaoLoginResult.authenticated(tokenPair);
        }

        KakaoIdentityFingerprint fingerprint = fingerprintGenerator.generateActive(user.providerSubject());
        return KakaoLoginResult.signUpRequired(
                kakaoSignUpTicketService.create(
                        TokenNamespace.CONSUMER, fingerprint.keyVersion(), fingerprint.value()));
    }

    public KakaoLinkResult linkKakao(Long accountId, KakaoAuthenticationRequest request) {
        KakaoOAuthState state = kakaoOAuthStateService.parse(request.state());
        if (state.namespace() != TokenNamespace.CONSUMER
                || state.purpose() != KakaoOAuthPurpose.LINK
                || !accountId.equals(state.accountId())) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }

        KakaoOAuthUser user = kakaoOAuthClient.authenticate(request.authorizationCode(), request.redirectUri());
        return consumerKakaoLinkTransactionService.linkActiveAccount(accountId, user.providerSubject());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public KakaoLoginResult signUp(ConsumerKakaoSignUpRequest request) {
        KakaoSignUpTicket ticket = kakaoSignUpTicketService.parse(request.signUpTicket());
        if (ticket.namespace() != TokenNamespace.CONSUMER) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }
        if (!fingerprintGenerator.isActiveKeyVersion(ticket.fingerprintKeyVersion())) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
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

        ConsumerAccount account = ConsumerAccount.createWithContact(
                request.email(), null, nicknamePolicy.normalize(request.nickname()), normalizedPhone,
                contactReferenceGenerator.generate());
        try {
            ConsumerAccount saved = consumerAccountRepository.saveAndFlush(account);
            KakaoLinkResult linkResult = kakaoSocialLoginLinkService.linkFingerprint(
                    TokenNamespace.CONSUMER,
                    saved.getId(),
                    new KakaoIdentityFingerprint(
                            ticket.fingerprintKeyVersion(), ticket.providerSubjectFingerprint()));
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
                jwtTokenProvider.generateAccessToken(TokenNamespace.CONSUMER, accountId),
                jwtTokenProvider.generateRefreshToken(TokenNamespace.CONSUMER, accountId));
    }

    private ServiceException mapDuplicateConstraint(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("uk_consumer_accounts_email")) {
            return new ServiceException(AccountErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (message != null && message.contains("uk_consumer_accounts_phone")) {
            return new ServiceException(AccountErrorCode.PHONE_ALREADY_EXISTS);
        }
        throw exception;
    }
}
