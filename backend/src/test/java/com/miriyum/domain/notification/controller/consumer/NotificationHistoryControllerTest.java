package com.miriyum.domain.notification.controller.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.jwt.JwtTokenProvider;
import com.miriyum.domain.auth.jwt.ParsedToken;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.ratelimit.RateLimiter;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.notification.dto.response.NotificationHistoryItemResponse;
import com.miriyum.domain.notification.dto.response.NotificationHistoryPageResponse;
import com.miriyum.domain.notification.dto.response.NotificationResourceResponse;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.service.NotificationHistoryService;
import com.miriyum.domain.notification.exception.NotificationErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.security.SecurityConfig;
import java.time.OffsetDateTime;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationHistoryController.class)
@Import(SecurityConfig.class)
class NotificationHistoryControllerTest {

    private static final long CONSUMER_ID = 11L;
    private static final String TOKEN = "valid-consumer-token";

    @Autowired MockMvc mockMvc;
    @MockitoBean NotificationHistoryService historyService;
    @MockitoBean ConsumerAccountService consumerAccountService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean RateLimiter rateLimiter;

    @BeforeEach
    void authenticateConsumerToken() {
        given(jwtTokenProvider.parseAccessToken(TOKEN))
                .willReturn(new ParsedToken(TokenNamespace.CONSUMER, CONSUMER_ID));
    }

    @Test
    void authenticatedActiveConsumerGetsOnlyPublicHistoryFields() throws Exception {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-13T01:02:03Z");
        var item = new NotificationHistoryItemResponse(
                "103",
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                "픽업 예약이 확정되었습니다.",
                new NotificationResourceResponse(
                        NotificationResourceType.PICKUP_RESERVATION, "31"),
                occurredAt,
                occurredAt.plusSeconds(1),
                occurredAt.plusSeconds(2),
                null
        );
        given(historyService.getHistory(CONSUMER_ID, null, 20))
                .willReturn(new NotificationHistoryPageResponse(List.of(item), false, null));

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].notificationId").value("103"))
                .andExpect(jsonPath("$.data.items[0].deliveredAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].action").isEmpty())
                .andExpect(jsonPath("$.data.items[0].deliveryStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].attemptCount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].correlationId").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());

        InOrder order = inOrder(consumerAccountService, historyService);
        order.verify(consumerAccountService).requireActiveAccount(CONSUMER_ID);
        order.verify(historyService).getHistory(CONSUMER_ID, null, 20);
    }

    @Test
    void malformedCursorReturnsNotificationSpecificError() throws Exception {
        given(historyService.getHistory(CONSUMER_ID, "contains.dot", 20))
                .willThrow(new ServiceException(NotificationErrorCode.INVALID_HISTORY_CURSOR));

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .queryParam("cursor", "contains.dot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_001"));
    }

    @Test
    void oversizedPageIsRejectedBeforeBusinessQuery() throws Exception {
        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .queryParam("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(consumerAccountService, historyService);
    }

    @Test
    void currentAccountAvailabilityFailureReturnsServiceUnavailable() throws Exception {
        willThrow(new CannotAcquireLockException("account lookup timed out"))
                .given(consumerAccountService).requireActiveAccount(CONSUMER_ID);

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));

        verifyNoInteractions(historyService);
    }

    @Test
    void historyStorageAvailabilityFailureReturnsServiceUnavailable() throws Exception {
        given(historyService.getHistory(CONSUMER_ID, null, 20))
                .willThrow(new DataAccessResourceFailureException("history database unavailable"));

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_012"));
    }

    @Test
    void badSqlProgrammingFailureRemainsInternalServerError() throws Exception {
        given(historyService.getHistory(CONSUMER_ID, null, 20))
                .willThrow(new BadSqlGrammarException(
                        "history query", "SELECT broken", new SQLException("syntax error")));

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_011"));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/consumers/me/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        verifyNoInteractions(consumerAccountService, historyService);
    }

    @Test
    void currentRestrictedAccountIsRevalidatedOnEveryRequest() throws Exception {
        willThrow(new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED))
                .given(consumerAccountService).requireActiveAccount(CONSUMER_ID);

        mockMvc.perform(get("/api/v1/consumers/me/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_011"));

        verifyNoInteractions(historyService);
    }
}
