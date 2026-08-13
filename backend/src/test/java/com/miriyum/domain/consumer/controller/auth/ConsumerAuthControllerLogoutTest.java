package com.miriyum.domain.consumer.controller.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.auth.cookie.AuthCookieFactory;
import com.miriyum.domain.auth.cookie.CsrfTokenGenerator;
import com.miriyum.domain.auth.cookie.OriginValidator;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.consumer.service.ConsumerAuthService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 로그아웃이 서버 폐기 성공 여부와 무관하게 브라우저 Refresh 쿠키를 만료시키는지 확인한다.
 *
 * <p>Valkey 장애로 폐기가 실패하면 {@code COMMON_012}가 나가는데, 이때 만료 쿠키까지 빠지면
 * 사용자는 로그아웃했다고 믿지만 브라우저에는 유효한 Refresh Token이 남는다(PR #208 리뷰).</p>
 */
@ExtendWith(MockitoExtension.class)
class ConsumerAuthControllerLogoutTest {

    private static final TokenNamespace NAMESPACE = TokenNamespace.CONSUMER;
    private static final String CSRF_TOKEN = "consumer-logout-csrf-token";

    @Mock
    private ConsumerAuthService consumerAuthService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private OriginValidator originValidator;

    private ConsumerAuthController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        controller = new ConsumerAuthController(
                consumerAuthService,
                jwtTokenProvider,
                new AuthCookieFactory(),
                new CsrfTokenGenerator(),
                originValidator);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("Valkey 장애로 폐기가 503으로 실패해도 Refresh 쿠키 만료 헤더는 내려간다")
    void logoutExpiresRefreshCookieEvenWhenRevokeFails() {
        // given
        request.setCookies(
                new Cookie(NAMESPACE.refreshCookieName(), "refresh-token"),
                new Cookie(NAMESPACE.csrfCookieName(), CSRF_TOKEN));
        willThrow(new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE))
                .given(consumerAuthService)
                .logout("refresh-token");

        // when & then
        assertThatThrownBy(() -> controller.logout(request, response, CSRF_TOKEN))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);

        assertThat(expiredRefreshCookieHeader()).isTrue();
    }

    @Test
    @DisplayName("정상 로그아웃도 Refresh 쿠키 만료 헤더를 내려보낸다")
    void logoutExpiresRefreshCookieOnSuccess() {
        // given
        request.setCookies(
                new Cookie(NAMESPACE.refreshCookieName(), "refresh-token"),
                new Cookie(NAMESPACE.csrfCookieName(), CSRF_TOKEN));

        // when
        controller.logout(request, response, CSRF_TOKEN);

        // then
        assertThat(expiredRefreshCookieHeader()).isTrue();
    }

    @Test
    @DisplayName("CSRF 검증에 실패하면 쿠키를 만료시키지 않고 폐기도 호출하지 않는다")
    void logoutDoesNotExpireCookieWhenCsrfRejected() {
        // given
        request.setCookies(
                new Cookie(NAMESPACE.refreshCookieName(), "refresh-token"),
                new Cookie(NAMESPACE.csrfCookieName(), CSRF_TOKEN));

        // when & then
        assertThatThrownBy(() -> controller.logout(request, response, "다른-토큰"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.CSRF_TOKEN_INVALID);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        verifyNoInteractions(consumerAuthService);
    }

    private boolean expiredRefreshCookieHeader() {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(header -> header.startsWith(NAMESPACE.refreshCookieName() + "=")
                        && header.contains("Max-Age=0"));
    }
}
