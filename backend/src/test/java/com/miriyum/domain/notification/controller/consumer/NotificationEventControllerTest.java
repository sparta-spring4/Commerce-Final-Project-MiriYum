package com.miriyum.domain.notification.controller.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.global.security.SecurityConfig;
import com.miriyum.global.sse.SseStreamScope;
import com.miriyum.global.sse.SseStreamService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(NotificationEventController.class)
@Import(SecurityConfig.class)
class NotificationEventControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-19T02:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean SseStreamService streams;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RateLimiter rateLimiter;

    @Test
    void authenticatedConsumerOpensOwnNotificationStreamWithReconnectCursor() throws Exception {
        given(jwtTokenProvider.parseAccessToken("consumer-token"))
                .willReturn(new ParsedToken(
                        TokenNamespace.CONSUMER, 41L, null, null, null, null, EXPIRES_AT));
        given(streams.open(
                SseStreamScope.notificationConsumer(41L), "opaque-cursor", EXPIRES_AT))
                .willReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/consumers/me/notification-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token")
                        .header("Last-Event-ID", "opaque-cursor"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        then(streams).should().open(
                SseStreamScope.notificationConsumer(41L), "opaque-cursor", EXPIRES_AT);
    }

    @Test
    void missingTokenCannotOpenNotificationStream() throws Exception {
        mockMvc.perform(get("/api/v1/consumers/me/notification-events"))
                .andExpect(status().isUnauthorized());

        then(streams).shouldHaveNoInteractions();
    }
}
