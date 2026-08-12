package com.miriyum.domain.consumer.controller.auth;

import com.miriyum.domain.auth.cookie.AuthCookieFactory;
import com.miriyum.domain.auth.cookie.CookieExtractor;
import com.miriyum.domain.auth.cookie.CsrfTokenGenerator;
import com.miriyum.domain.auth.cookie.KakaoOAuthStateCookieFactory;
import com.miriyum.domain.auth.cookie.OriginValidator;
import com.miriyum.domain.auth.dto.request.EmptyJsonRequest;
import com.miriyum.domain.auth.dto.request.KakaoAuthorizationRequest;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.CsrfTokenResponse;
import com.miriyum.domain.auth.dto.response.KakaoAuthorizationResponse;
import com.miriyum.domain.auth.dto.response.KakaoLoginResponse;
import com.miriyum.domain.auth.dto.response.TokenResponse;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.auth.social.dto.KakaoAuthenticationRequest;
import com.miriyum.domain.auth.social.dto.KakaoAuthorization;
import com.miriyum.domain.auth.social.dto.KakaoLoginResult;
import com.miriyum.domain.auth.social.enums.KakaoLoginStatus;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import com.miriyum.domain.consumer.dto.auth.ConsumerKakaoSignUpRequest;
import com.miriyum.domain.consumer.dto.auth.ConsumerSignUpRequest;
import com.miriyum.domain.consumer.service.ConsumerAuthService;
import com.miriyum.domain.consumer.service.ConsumerKakaoAuthService;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 일반 사용자 가입·로그인·재발급·로그아웃·CSRF 준비 API다.
 */
@RestController
@RequestMapping("/api/v1/consumers/auth")
@RequiredArgsConstructor
public class ConsumerAuthController {

    private static final TokenNamespace NAMESPACE = TokenNamespace.CONSUMER;

    private final ConsumerAuthService consumerAuthService;
    private final ConsumerKakaoAuthService consumerKakaoAuthService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieFactory authCookieFactory;
    private final KakaoOAuthStateCookieFactory kakaoOAuthStateCookieFactory;
    private final CsrfTokenGenerator csrfTokenGenerator;
    private final OriginValidator originValidator;

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountCreatedResponse> signUp(@Valid @RequestBody ConsumerSignUpRequest request) {
        return ApiResponse.success("가입이 완료됐습니다.", consumerAuthService.signUp(request));
    }

    @PostMapping("/kakao/authorizations")
    public ApiResponse<KakaoAuthorizationResponse> createKakaoAuthorization(
            @Valid @RequestBody KakaoAuthorizationRequest request,
            HttpServletResponse response
    ) {
        KakaoAuthorization authorization = consumerKakaoAuthService.createLoginAuthorization(request.redirectUri());
        setKakaoStateCookie(response, KakaoOAuthPurpose.LOGIN, authorization.state());
        return ApiResponse.success("카카오 로그인 주소를 발급했습니다.", new KakaoAuthorizationResponse(
                authorization.authorizationUrl()));
    }

    @PostMapping("/kakao/sessions")
    public ApiResponse<KakaoLoginResponse> loginWithKakao(
            @Valid @RequestBody KakaoAuthenticationRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        try {
            requireKakaoState(httpRequest, KakaoOAuthPurpose.LOGIN, request.state());
            KakaoLoginResult result = consumerKakaoAuthService.authenticate(request);
            setRefreshCookieWhenAuthenticated(response, result);
            return ApiResponse.success("카카오 로그인 결과를 확인했습니다.", toKakaoLoginResponse(result));
        } finally {
            expireKakaoStateCookie(response, KakaoOAuthPurpose.LOGIN);
        }
    }

    @PostMapping("/kakao/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KakaoLoginResponse> signUpWithKakao(
            @Valid @RequestBody ConsumerKakaoSignUpRequest request,
            HttpServletResponse response
    ) {
        KakaoLoginResult result = consumerKakaoAuthService.signUp(request);
        setRefreshCookieWhenAuthenticated(response, result);
        return ApiResponse.success("카카오 가입이 완료됐습니다.", toKakaoLoginResponse(result));
    }

    @PostMapping("/sessions")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        TokenPair tokenPair = consumerAuthService.login(request);
        setRefreshCookie(response, tokenPair.refreshToken());
        return ApiResponse.success("로그인했습니다.", toTokenResponse(tokenPair));
    }

    @PostMapping("/token-refreshes")
    public ApiResponse<TokenResponse> refresh(
            @RequestBody EmptyJsonRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        requireSameOrigin(request);
        String refreshToken = CookieExtractor.extract(request, NAMESPACE.refreshCookieName());
        TokenPair tokenPair = consumerAuthService.refresh(refreshToken);
        setRefreshCookie(response, tokenPair.refreshToken());
        return ApiResponse.success("토큰을 재발급했습니다.", toTokenResponse(tokenPair));
    }

    @GetMapping("/csrf-tokens/current")
    public ApiResponse<CsrfTokenResponse> currentCsrfToken(HttpServletResponse response) {
        String token = csrfTokenGenerator.generate();
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.csrfCookie(NAMESPACE, token).toString());
        return ApiResponse.success("CSRF 토큰을 발급했습니다.", CsrfTokenResponse.of(token));
    }

    @DeleteMapping("/sessions/current")
    public ApiResponse<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfHeader
    ) {
        String refreshToken = CookieExtractor.extract(request, NAMESPACE.refreshCookieName());
        String csrfCookie = CookieExtractor.extract(request, NAMESPACE.csrfCookieName());

        if (!csrfTokenGenerator.matches(csrfCookie, csrfHeader)) {
            throw new ServiceException(AuthErrorCode.CSRF_TOKEN_INVALID);
        }

        consumerAuthService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshCookie(NAMESPACE).toString());
        return ApiResponse.success("로그아웃했습니다.", null);
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (!originValidator.isSameOrigin(origin, referer)) {
            throw new ServiceException(AuthErrorCode.ORIGIN_REJECTED);
        }
    }

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.refreshCookie(NAMESPACE, refreshToken).toString());
    }

    private void setKakaoStateCookie(HttpServletResponse response, KakaoOAuthPurpose purpose, String state) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                kakaoOAuthStateCookieFactory.activeCookie(NAMESPACE, purpose, state).toString());
    }

    private void expireKakaoStateCookie(HttpServletResponse response, KakaoOAuthPurpose purpose) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                kakaoOAuthStateCookieFactory.expiredCookie(NAMESPACE, purpose).toString());
    }

    private void requireKakaoState(HttpServletRequest request, KakaoOAuthPurpose purpose, String state) {
        String cookieState = CookieExtractor.extract(
                request, kakaoOAuthStateCookieFactory.cookieName(NAMESPACE, purpose));
        if (!kakaoOAuthStateCookieFactory.matchesRequestState(cookieState, state)) {
            throw new ServiceException(AuthErrorCode.KAKAO_OAUTH_INVALID);
        }
    }

    private TokenResponse toTokenResponse(TokenPair tokenPair) {
        return TokenResponse.of(tokenPair.accessToken(), jwtTokenProvider.getAccessTokenValiditySeconds());
    }

    private void setRefreshCookieWhenAuthenticated(HttpServletResponse response, KakaoLoginResult result) {
        if (result.status() == KakaoLoginStatus.AUTHENTICATED) {
            setRefreshCookie(response, result.tokenPair().refreshToken());
        }
    }

    private KakaoLoginResponse toKakaoLoginResponse(KakaoLoginResult result) {
        return KakaoLoginResponse.from(result, jwtTokenProvider.getAccessTokenValiditySeconds());
    }
}
