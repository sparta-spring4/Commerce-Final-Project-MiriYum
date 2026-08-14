package com.miriyum.domain.platformoperator.controller.auth;

import com.miriyum.domain.auth.cookie.AuthCookieFactory;
import com.miriyum.domain.auth.cookie.CookieExtractor;
import com.miriyum.domain.auth.cookie.CsrfTokenGenerator;
import com.miriyum.domain.auth.cookie.OriginValidator;
import com.miriyum.domain.auth.dto.request.EmptyJsonRequest;
import com.miriyum.domain.auth.dto.request.LoginRequest;
import com.miriyum.domain.auth.dto.response.CsrfTokenResponse;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.platformoperator.dto.auth.InitialPasswordChangeRequest;
import com.miriyum.domain.platformoperator.dto.auth.PlatformOperatorTokenResult;
import com.miriyum.domain.platformoperator.dto.auth.PlatformOperatorTokenResponse;
import com.miriyum.domain.platformoperator.service.PlatformOperatorAuthService;
import com.miriyum.domain.platformoperator.session.PlatformOperatorPrincipal;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform-operators/auth")
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorAuthController {
    private static final TokenNamespace NAMESPACE = TokenNamespace.PLATFORM_OPERATOR;
    private final PlatformOperatorAuthService service;
    private final AuthCookieFactory cookies;
    private final CsrfTokenGenerator csrf;
    private final OriginValidator originValidator;
    private final Clock clock;

    public PlatformOperatorAuthController(
            PlatformOperatorAuthService service, AuthCookieFactory cookies, CsrfTokenGenerator csrf,
            OriginValidator originValidator, Clock clock) {
        this.service = service;
        this.cookies = cookies;
        this.csrf = csrf;
        this.originValidator = originValidator;
        this.clock = clock;
    }

    @PostMapping("/sessions")
    public ApiResponse<PlatformOperatorTokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return tokenResponse("로그인했습니다.", service.login(request), response);
    }

    @PostMapping("/token-refreshes")
    public ApiResponse<PlatformOperatorTokenResponse> refresh(
            @RequestBody EmptyJsonRequest ignored, HttpServletRequest request, HttpServletResponse response) {
        requireSameOrigin(request);
        return tokenResponse("토큰을 재발급했습니다.",
                service.refresh(CookieExtractor.extract(request, NAMESPACE.refreshCookieName())), response);
    }

    @GetMapping("/csrf-tokens/current")
    public ApiResponse<CsrfTokenResponse> csrfToken(HttpServletResponse response) {
        String token = csrf.generate();
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.csrfCookie(NAMESPACE, token).toString());
        return ApiResponse.success("CSRF 토큰을 발급했습니다.", CsrfTokenResponse.of(token));
    }

    @DeleteMapping("/sessions/current")
    public ApiResponse<Void> logout(
            HttpServletRequest request, HttpServletResponse response,
            @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfHeader) {
        String csrfCookie = CookieExtractor.extract(request, NAMESPACE.csrfCookieName());
        if (!csrf.matches(csrfCookie, csrfHeader)) throw new ServiceException(AuthErrorCode.CSRF_TOKEN_INVALID);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.expiredRefreshCookie(NAMESPACE).toString());
        service.logout(CookieExtractor.extract(request, NAMESPACE.refreshCookieName()));
        return ApiResponse.success("로그아웃했습니다.", null);
    }

    @PutMapping("/initial-password")
    public ApiResponse<PlatformOperatorTokenResponse> changeInitialPassword(
            @AuthenticationPrincipal PlatformOperatorPrincipal principal,
            @Valid @RequestBody InitialPasswordChangeRequest request,
            HttpServletResponse response) {
        return tokenResponse("최초 비밀번호를 변경했습니다.", service.changeInitialPassword(principal, request), response);
    }

    private ApiResponse<PlatformOperatorTokenResponse> tokenResponse(
            String message, PlatformOperatorTokenResult result, HttpServletResponse response) {
        Duration maxAge = Duration.between(clock.instant(), result.absoluteExpiresAt());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.refreshCookie(NAMESPACE, result.refreshToken(), maxAge.isNegative() ? Duration.ZERO : maxAge)
                        .toString());
        return ApiResponse.success(message, PlatformOperatorTokenResponse.from(result));
    }

    private void requireSameOrigin(HttpServletRequest request) {
        if (!originValidator.isSameOrigin(request.getHeader(HttpHeaders.ORIGIN), request.getHeader(HttpHeaders.REFERER))) {
            throw new ServiceException(AuthErrorCode.ORIGIN_REJECTED);
        }
    }
}
