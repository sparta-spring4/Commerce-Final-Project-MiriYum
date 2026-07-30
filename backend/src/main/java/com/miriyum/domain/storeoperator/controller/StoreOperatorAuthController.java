package com.miriyum.domain.storeoperator.controller;

import com.miriyum.domain.auth.cookie.AuthCookieFactory;
import com.miriyum.domain.auth.cookie.CookieExtractor;
import com.miriyum.domain.auth.cookie.CsrfTokenGenerator;
import com.miriyum.domain.auth.cookie.OriginValidator;
import com.miriyum.domain.auth.dto.request.EmptyJsonRequest;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.AccountCreatedResponse;
import com.miriyum.domain.auth.dto.response.CsrfTokenResponse;
import com.miriyum.domain.auth.dto.response.TokenResponse;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.jwt.TokenPair;
import com.miriyum.domain.storeoperator.dto.request.StoreOperatorSignUpRequest;
import com.miriyum.domain.storeoperator.service.StoreOperatorAuthService;
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
 * 매장 운영자 가입·로그인·재발급·로그아웃·CSRF 준비 API다.
 */
@RestController
@RequestMapping("/api/v1/store-operator-auth")
@RequiredArgsConstructor
public class StoreOperatorAuthController {

    private static final TokenNamespace NAMESPACE = TokenNamespace.STORE_OPERATOR;

    private final StoreOperatorAuthService storeOperatorAuthService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieFactory authCookieFactory;
    private final CsrfTokenGenerator csrfTokenGenerator;
    private final OriginValidator originValidator;

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AccountCreatedResponse> signUp(@Valid @RequestBody StoreOperatorSignUpRequest request) {
        return ApiResponse.success("가입이 완료됐습니다.", storeOperatorAuthService.signUp(request));
    }

    @PostMapping("/sessions")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        TokenPair tokenPair = storeOperatorAuthService.login(request);
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
        TokenPair tokenPair = storeOperatorAuthService.refresh(refreshToken);
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

        storeOperatorAuthService.logout(refreshToken);
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

    private TokenResponse toTokenResponse(TokenPair tokenPair) {
        return TokenResponse.of(tokenPair.accessToken(), jwtTokenProvider.getAccessTokenValiditySeconds());
    }
}
