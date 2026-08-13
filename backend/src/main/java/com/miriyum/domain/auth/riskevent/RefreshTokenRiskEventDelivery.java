package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Valkey pending 위험 사건을 MySQL에 전달하고 성공한 marker만 제거한다. */
@Component
@ConditionalOnProperty(
        name = "miriyum.auth.refresh-risk-event-delivery.enabled",
        havingValue = "true"
)
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
            long pendingEventCount = markerStore.pendingEventCount();
            if (pendingEventCount > 0) {
                log.info("refresh_token_risk_event_pending_count pending_count {}", pendingEventCount);
            }
        } catch (DataAccessException | ServiceException exception) {
            recordFailure("valkey_read");
            return 0;
        }

        int delivered = 0;
        String failureStage = null;
        for (PendingRefreshTokenRiskEvent event : events) {
            try {
                authRiskEventStore.record(event);
            } catch (DataAccessException | ServiceException exception) {
                failureStage = firstFailureStage(failureStage, "mysql_write");
                continue;
            }
            try {
                if (markerStore.deleteIfUnchanged(event.eventKey(), event.occurrenceCount())) {
                    delivered++;
                }
            } catch (DataAccessException | ServiceException exception) {
                failureStage = firstFailureStage(failureStage, "valkey_delete");
            }
        }
        if (failureStage != null) {
            recordFailure(failureStage);
        } else {
            consecutiveFailures.set(0);
        }
        return delivered;
    }

    private String firstFailureStage(String currentStage, String newStage) {
        return currentStage == null ? newStage : currentStage;
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
