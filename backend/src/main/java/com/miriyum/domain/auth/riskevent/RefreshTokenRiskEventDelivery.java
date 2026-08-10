package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Valkey pending 위험 사건을 MySQL에 전달하고 성공한 marker만 제거한다. */
@Component
public class RefreshTokenRiskEventDelivery {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRiskEventDelivery.class);

    private final ValkeyRefreshTokenRiskEventMarkerStore markerStore;
    private final AuthRiskEventStore authRiskEventStore;
    private final int alertThreshold;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public RefreshTokenRiskEventDelivery(
            ValkeyRefreshTokenRiskEventMarkerStore markerStore,
            AuthRiskEventStore authRiskEventStore,
            @Value("${miriyum.auth.refresh-risk-event-delivery-alert-threshold:10}")
            int alertThreshold
    ) {
        this.markerStore = markerStore;
        this.authRiskEventStore = authRiskEventStore;
        this.alertThreshold = Math.max(1, alertThreshold);
    }

    @Scheduled(fixedDelayString = "${miriyum.auth.refresh-risk-event-delivery-delay-ms:30000}")
    public void deliverPendingEventsOnSchedule() {
        deliverPendingEvents();
    }

    public int deliverPendingEvents() {
        List<PendingRefreshTokenRiskEvent> events;
        try {
            events = markerStore.findPendingEvents();
        } catch (DataAccessException | ServiceException exception) {
            recordFailure("valkey_read");
            return 0;
        }

        int delivered = 0;
        boolean deliveryFailed = false;
        for (PendingRefreshTokenRiskEvent event : events) {
            try {
                authRiskEventStore.record(event);
                markerStore.delete(event.eventKey());
                delivered++;
            } catch (DataAccessException | ServiceException exception) {
                deliveryFailed = true;
            }
        }
        if (deliveryFailed) {
            recordFailure("mysql_delivery");
        } else {
            consecutiveFailures.set(0);
        }
        return delivered;
    }

    private void recordFailure(String failureStage) {
        int failureCount = consecutiveFailures.incrementAndGet();
        if (failureCount >= alertThreshold) {
            log.error(
                    "event=refresh_token_risk_event_delivery_stalled "
                            + "consecutive_failures={} failure_stage={}",
                    failureCount,
                    failureStage);
        }
    }
}
