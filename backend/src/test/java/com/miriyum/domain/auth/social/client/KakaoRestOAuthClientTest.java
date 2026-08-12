package com.miriyum.domain.auth.social.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.miriyum.domain.auth.social.config.KakaoOAuthProperties;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class KakaoRestOAuthClientTest {

    private static final String REDIRECT_URI = "https://app.example.com/kakao/callback";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";

    private MockRestServiceServer server;
    private KakaoRestOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoRestOAuthClient(
                new KakaoOAuthProperties(true, "rest-api-key", "client-secret", REDIRECT_URI),
                new ObjectMapper(),
                builder.build());
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
