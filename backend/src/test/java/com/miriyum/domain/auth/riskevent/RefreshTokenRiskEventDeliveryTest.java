package com.miriyum.domain.auth.riskevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRiskEventDeliveryTest {

    @Mock
    private ValkeyRefreshTokenRiskEventMarkerStore markerStore;

    @Mock
    private AuthRiskEventStore authRiskEventStore;

    @InjectMocks
    private RefreshTokenRiskEventDelivery delivery;

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
