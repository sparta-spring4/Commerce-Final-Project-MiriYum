package com.miriyum.domain.reservation.waiting.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.reservation.waiting.controller.consumer.WaitingConsumerEventController;
import com.miriyum.domain.reservation.waiting.controller.storeoperator.WaitingStoreOperatorEventController;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
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

@WebMvcTest({
        WaitingConsumerEventController.class,
        WaitingStoreOperatorEventController.class
})
@Import(SecurityConfig.class)
class WaitingEventControllerTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-19T02:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean SseStreamService streams;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RateLimiter rateLimiter;

    @Test
    void consumerOpensAccountScopedWaitingStream() throws Exception {
        authenticate("consumer-token", TokenNamespace.CONSUMER, 41L);
        given(streams.open(SseStreamScope.waitingConsumer(41L), null, EXPIRES_AT))
                .willReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/consumers/me/waiting-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        then(streams).should().open(SseStreamScope.waitingConsumer(41L), null, EXPIRES_AT);
    }

    @Test
    void storeOperatorOpensStoreScopedWaitingStreamWithReconnectCursor() throws Exception {
        authenticate("store-token", TokenNamespace.STORE_OPERATOR, 33L);
        given(streams.open(
                SseStreamScope.waitingStoreOperator(33L, 22L), "opaque-cursor", EXPIRES_AT))
                .willReturn(new SseEmitter());

        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                        .header("Last-Event-ID", "opaque-cursor"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        then(streams).should().open(
                SseStreamScope.waitingStoreOperator(33L, 22L),
                "opaque-cursor", EXPIRES_AT);
    }

    @Test
    void consumerTokenCannotCrossStoreOperatorEventBoundary() throws Exception {
        authenticate("consumer-token", TokenNamespace.CONSUMER, 41L);

        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer consumer-token"))
                .andExpect(status().isUnauthorized());

        then(streams).shouldHaveNoInteractions();
    }

    @Test
    void unauthorizedStoreFailsAsJsonBeforeTheStreamStarts() throws Exception {
        authenticate("store-token", TokenNamespace.STORE_OPERATOR, 33L);
        given(streams.open(
                SseStreamScope.waitingStoreOperator(33L, 22L), null, EXPIRES_AT))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    void missingStoreFailsAsJsonBeforeTheStreamStarts() throws Exception {
        authenticate("store-token", TokenNamespace.STORE_OPERATOR, 33L);
        given(streams.open(
                SseStreamScope.waitingStoreOperator(33L, 22L), null, EXPIRES_AT))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/store-operators/stores/22/waiting-events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }

    private void authenticate(String token, TokenNamespace namespace, long accountId) {
        given(jwtTokenProvider.parseAccessToken(token))
                .willReturn(new ParsedToken(
                        namespace, accountId, null, null, null, null, EXPIRES_AT));
    }
}
