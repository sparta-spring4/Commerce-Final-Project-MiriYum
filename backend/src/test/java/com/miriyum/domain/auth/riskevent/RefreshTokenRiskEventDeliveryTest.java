package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRiskEventDeliveryTest {

    @Mock
    private ValkeyRefreshTokenRiskEventMarkerStore markerStore;

    @Mock
    private AuthRiskEventStore authRiskEventStore;

    private RefreshTokenRiskEventDelivery delivery;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        delivery = new RefreshTokenRiskEventDelivery(markerStore, authRiskEventStore, 3);
        logger = (Logger) LoggerFactory.getLogger(RefreshTokenRiskEventDelivery.class);
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
    @DisplayName("MySQL 위험 사건 저장이 실패하면 pending marker를 남겨 다음 전달에서 재시도한다")
    void retriesPendingMarkerAfterDurableStorageFailure() {
        PendingRefreshTokenRiskEvent event = event();
        given(markerStore.findPendingEvents()).willReturn(List.of(event));
        willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .willDoNothing()
                .given(authRiskEventStore).record(event);

        int firstDelivered = delivery.deliverPendingEvents();
        int secondDelivered = delivery.deliverPendingEvents();

        assertThat(firstDelivered).isZero();
        assertThat(secondDelivered).isEqualTo(1);
        verify(authRiskEventStore, times(2)).record(event);
        verify(markerStore).delete(event.eventKey());
    }

    @Test
    @DisplayName("MySQL 전달이 연속 임계치만큼 실패하면 민감값 없는 운영 경보 로그를 남긴다")
    void logsRestrictedOperationalSignalAfterRepeatedMysqlFailures() {
        PendingRefreshTokenRiskEvent event = event();
        given(markerStore.findPendingEvents()).willReturn(List.of(event));
        willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .given(authRiskEventStore).record(event);

        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();

        List<String> messages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(messages).containsExactly(
                "event=refresh_token_risk_event_delivery_stalled "
                        + "consecutive_failures=3 failure_stage=mysql_delivery");
        assertThat(messages.getFirst())
                .doesNotContain(event.eventKey(), event.familyId(), event.tokenHash());
    }

    @Test
    @DisplayName("Valkey pending 조회가 연속 임계치만큼 실패해도 동일한 운영 경보 로그를 남긴다")
    void logsOperationalSignalAfterRepeatedValkeyFailures() {
        given(markerStore.findPendingEvents())
                .willThrow(new com.miriyum.global.exception.ServiceException(
                        com.miriyum.global.exception.CommonErrorCode.SERVICE_UNAVAILABLE));

        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();
        delivery.deliverPendingEvents();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "event=refresh_token_risk_event_delivery_stalled "
                                + "consecutive_failures=3 failure_stage=valkey_read");
    }

    @Test
    @DisplayName("정상 전달이 한 번 성공하면 연속 실패 횟수를 초기화한다")
    void resetsConsecutiveFailuresAfterSuccessfulDelivery() {
        PendingRefreshTokenRiskEvent event = event();
        given(markerStore.findPendingEvents()).willReturn(List.of(event));
        willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .willDoNothing()
                .willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .willThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .given(authRiskEventStore).record(event);

        for (int attempt = 0; attempt < 5; attempt++) {
            delivery.deliverPendingEvents();
        }

        assertThat(logAppender.list).isEmpty();
    }

    private PendingRefreshTokenRiskEvent event() {
        return new PendingRefreshTokenRiskEvent(
                "auth:risk:pending:consumer:family-1:"
                        + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                TokenNamespace.CONSUMER,
                7L,
                "family-1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "REUSED_ROTATED_TOKEN",
                "ROTATION",
                "AUTH-012-v1",
                Instant.parse("2026-08-10T00:00:00Z"));
    }
}
