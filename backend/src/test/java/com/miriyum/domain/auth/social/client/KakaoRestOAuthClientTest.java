package com.miriyum.domain.auth.social.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.domain.auth.social.config.KakaoOAuthProperties;
import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class KakaoRestOAuthClientTest {

    private static final String REDIRECT_URI = "https://app.example.com/kakao/callback";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_URL = "https://kapi.kakao.com/v2/user/me";

    private MockRestServiceServer server;
    private KakaoRestOAuthClient client;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoRestOAuthClient(
                new KakaoOAuthProperties(true, "rest-api-key", "client-secret", REDIRECT_URI),
                new ObjectMapper(),
                builder.build());
        logger = (Logger) LoggerFactory.getLogger(KakaoRestOAuthClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("카카오 요청 제한 응답은 사용자 입력 오류가 아닌 일시적 서비스 장애로 처리한다")
    void mapsKakaoRateLimitToServiceUnavailable() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andRespond(withStatus(TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.authenticate("authorization-code", REDIRECT_URI))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("카카오 토큰 교환 뒤 사용자 조회까지 필요한 요청값으로 수행한다")
    void authenticatesWithTokenExchangeAndUserLookup() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("client_id=rest-api-key"),
                        org.hamcrest.Matchers.containsString("client_secret=client-secret"),
                        org.hamcrest.Matchers.containsString("code=authorization-code"),
                        org.hamcrest.Matchers.containsString("redirect_uri=https%3A%2F%2Fapp.example.com%2Fkakao%2Fcallback"))))
                .andRespond(withSuccess("{\"access_token\":\"kakao-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_URL))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer kakao-access-token"))
                .andRespond(withSuccess("{\"id\":123456789}", MediaType.APPLICATION_JSON));

        assertThat(client.authenticate("authorization-code", REDIRECT_URI).providerSubject())
                .isEqualTo("123456789");
        server.verify();
    }

    @Test
    @DisplayName("카카오가 잘못된 인가 코드를 거절하면 유효하지 않은 카카오 요청으로 처리한다")
    void mapsInvalidAuthorizationCodeToKakaoOAuthInvalid() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andRespond(withStatus(BAD_REQUEST));

        assertThatThrownBy(() -> client.authenticate("invalid-code", REDIRECT_URI))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.KAKAO_OAUTH_INVALID);
        server.verify();
    }

    @Test
    @DisplayName("카카오 4xx는 오류 코드와 상태만 남기고 OAuth 비밀값은 로그에 남기지 않는다")
    void logsSafeProviderFailureDetailsForKakaoClientError() {
        String authorizationCode = "authorization-code-must-not-be-logged";
        String clientSecret = "client-secret-must-not-be-logged";
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andRespond(withStatus(BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"secret must not be logged\"}"));

        assertThatThrownBy(() -> client.authenticate(authorizationCode, REDIRECT_URI))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.KAKAO_OAUTH_INVALID);

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("event=kakao_oauth_provider_rejected")
                        .contains("provider_status=400")
                        .contains("provider_error=invalid_client")
                        .doesNotContain(authorizationCode)
                        .doesNotContain(clientSecret)
                        .doesNotContain("secret must not be logged"));
        server.verify();
    }

    @Test
    @DisplayName("카카오 토큰 응답 본문이 비어 있으면 일시적 서비스 장애로 처리한다")
    void mapsEmptyKakaoResponseToServiceUnavailable() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authenticate("authorization-code", REDIRECT_URI))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("카카오 토큰 응답 JSON이 깨져 있으면 일시적 서비스 장애로 처리한다")
    void mapsMalformedKakaoResponseToServiceUnavailable() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authenticate("authorization-code", REDIRECT_URI))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE);
        server.verify();
    }
}
