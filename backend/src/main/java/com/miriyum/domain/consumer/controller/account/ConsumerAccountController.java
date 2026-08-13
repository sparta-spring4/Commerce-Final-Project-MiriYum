package com.miriyum.domain.consumer.controller.account;

import com.miriyum.domain.auth.cookie.CookieExtractor;
import com.miriyum.domain.auth.cookie.KakaoOAuthStateCookieFactory;
import com.miriyum.domain.auth.contact.PhoneNumberPolicy;
import com.miriyum.domain.auth.dto.request.KakaoAuthorizationRequest;
import com.miriyum.domain.auth.dto.response.KakaoAuthorizationResponse;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.AuthenticatedPrincipal;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoAuthorization;
import com.miriyum.domain.auth.social.enums.KakaoLinkResult;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountResponse;
import com.miriyum.domain.consumer.dto.account.ConsumerAccountUpdateRequest;
import com.miriyum.domain.consumer.dto.account.ConsumerContactRegistrationRequest;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.consumer.service.ConsumerKakaoAuthService;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.idempotency.RequestFingerprint;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 일반 사용자 본인 정보 조회·수정과 연락처 등록을 제공하는 마이페이지 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/consumers")
@RequiredArgsConstructor
public class ConsumerAccountController {

    private static final String UPDATE_COMMAND_TYPE = "CONSUMER_ACCOUNT_UPDATE";
    private static final String UPDATE_ROUTE = "PATCH /api/v1/consumers/me";
    private static final String CONTACT_COMMAND_TYPE = "CONSUMER_CONTACT_REGISTER";
    private static final String CONTACT_ROUTE = "PUT /api/v1/consumers/me/contact";

    private final ConsumerAccountService consumerAccountService;
    private final ConsumerKakaoAuthService consumerKakaoAuthService;
    private final KakaoOAuthStateCookieFactory kakaoOAuthStateCookieFactory;
    private final PhoneNumberPolicy phoneNumberPolicy;

    @GetMapping("/me")
    public ApiResponse<ConsumerAccountResponse> getMe(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success("조회했습니다.", consumerAccountService.getMe(principal.accountId()));
    }

    @PostMapping("/me/kakao/authorizations")
    public ApiResponse<KakaoAuthorizationResponse> createKakaoLinkAuthorization(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody KakaoAuthorizationRequest request,
            HttpServletResponse response
    ) {
        KakaoAuthorization authorization = consumerKakaoAuthService.createLinkAuthorization(
                principal.accountId(), request.redirectUri());
        setKakaoStateCookie(response, KakaoOAuthPurpose.LINK, authorization.state());
        return ApiResponse.success("카카오 연결 주소를 발급했습니다.", new KakaoAuthorizationResponse(
                authorization.authorizationUrl()));
    }

    @PostMapping("/me/kakao-links")
    public ApiResponse<KakaoLinkResult> linkKakao(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody KakaoAuthenticationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        try {
            requireKakaoState(httpRequest, KakaoOAuthPurpose.LINK, request.state());
            KakaoLinkResult result = consumerKakaoAuthService.linkKakao(principal.accountId(), request);
            return ApiResponse.success("카카오 계정을 연결했습니다.", result);
        } finally {
            expireKakaoStateCookie(response, KakaoOAuthPurpose.LINK);
        }
    }

    /**
     * 기존 계정의 최초 연락처를 등록한다. 실제 전화번호 소유 인증은 수행하지 않는다.
     */
    @PutMapping("/me/contact")
    public ApiResponse<JsonNode> registerContact(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConsumerContactRegistrationRequest request
    ) {
        consumerAccountService.requireActiveAccount(principal.accountId());
        IdempotencyKey key = IdempotencyKey.parse(idempotencyKey);
        IdempotencyCommand command = new IdempotencyCommand(
                TokenNamespace.CONSUMER.value(),
                principal.accountId(),
                CONTACT_COMMAND_TYPE,
                key.value(),
                RequestFingerprint.of(CONTACT_ROUTE + "\nphoneNumber="
                        + phoneNumberPolicy.normalize(request.phoneNumber())));

        IdempotentOutcome outcome = consumerAccountService.registerContact(
                command, principal.accountId(), request);
        return ApiResponse.success("연락처를 등록했습니다.", outcome.data());
    }

    /**
     * 닉네임을 수정한다. C-006에 따라 {@code Idempotency-Key}를 요구하고, 같은 키·같은 입력의
     * 재요청은 최초 결과를 그대로 재생한다(#32 공통 멱등 기반).
     *
     * <p>응답 {@code data}는 최초 실행이든 재생이든 저장된 같은 JSON을 그대로 내보내야 하므로
     * 도메인 DTO가 아니라 {@link JsonNode}로 받는다. 직렬화 결과는 {@code ConsumerAccount}
     * 스키마와 동일하다.</p>
     */
    @PatchMapping("/me")
    public ApiResponse<JsonNode> updateMe(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConsumerAccountUpdateRequest request
    ) {
        IdempotencyKey key = IdempotencyKey.parse(idempotencyKey);
        IdempotencyCommand command = new IdempotencyCommand(
                TokenNamespace.CONSUMER.value(),
                principal.accountId(),
                UPDATE_COMMAND_TYPE,
                key.value(),
                RequestFingerprint.of(canonicalUpdateInput(request)));

        IdempotentOutcome outcome = consumerAccountService.updateName(command, principal.accountId(), request);
        return ApiResponse.success("수정했습니다.", outcome.data());
    }

    /**
     * 지문 계산용 정규 입력이다. 대상 주체는 멱등 업무 키에 이미 포함되므로 경로와 승인된 body
     * field만 넣는다. 제출값을 그대로 쓰기 때문에 공백 등이 다른 요청은 다른 요청으로 취급된다.
     */
    private String canonicalUpdateInput(ConsumerAccountUpdateRequest request) {
        return UPDATE_ROUTE + "\nnickname=" + request.nickname();
    }

    private void setKakaoStateCookie(HttpServletResponse response, KakaoOAuthPurpose purpose, String state) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                kakaoOAuthStateCookieFactory.activeCookie(TokenNamespace.CONSUMER, purpose, state).toString());
    }

    private void expireKakaoStateCookie(HttpServletResponse response, KakaoOAuthPurpose purpose) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                kakaoOAuthStateCookieFactory.expiredCookie(TokenNamespace.CONSUMER, purpose).toString());
    }

    private void requireKakaoState(HttpServletRequest request, KakaoOAuthPurpose purpose, String state) {
        String cookieState = CookieExtractor.extract(
                request, kakaoOAuthStateCookieFactory.cookieName(TokenNamespace.CONSUMER, purpose));
        if (!kakaoOAuthStateCookieFactory.matchesRequestState(cookieState, state)) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }
    }
}
