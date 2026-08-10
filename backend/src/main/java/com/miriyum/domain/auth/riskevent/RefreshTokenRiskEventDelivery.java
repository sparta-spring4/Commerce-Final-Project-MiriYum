package com.miriyum.domain.auth.riskevent;

import com.miriyum.domain.auth.refreshtoken.PendingRefreshTokenRiskEvent;
import com.miriyum.domain.auth.refreshtoken.ValkeyRefreshTokenRiskEventMarkerStore;
import com.miriyum.global.exception.ServiceException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Valkey pending 위험 사건을 MySQL에 전달하고 성공한 marker만 제거한다. */
@Component
public class RefreshTokenRiskEventDelivery {

    private final ValkeyRefreshTokenRiskEventMarkerStore markerStore;
    private final AuthRiskEventStore authRiskEventStore;

    public RefreshTokenRiskEventDelivery(
            ValkeyRefreshTokenRiskEventMarkerStore markerStore,
            AuthRiskEventStore authRiskEventStore
    ) {
        this.markerStore = markerStore;
        this.authRiskEventStore = authRiskEventStore;
    }

    @Scheduled(fixedDelayString = "${miriyum.auth.refresh-risk-event-delivery-delay-ms:30000}")
    public void deliverPendingEventsOnSchedule() {
        deliverPendingEvents();
    }

    public int deliverPendingEvents() {
        List<PendingRefreshTokenRiskEvent> events = markerStore.findPendingEvents();
        int delivered = 0;
        for (PendingRefreshTokenRiskEvent event : events) {
            try {
                authRiskEventStore.record(event);
                markerStore.delete(event.eventKey());
                delivered++;
            } catch (DataAccessException | ServiceException exception) {
                // Marker를 보존해 다음 실행에서 다시 전달한다. 토큰 원문은 로그에 남기지 않는다.
            }
        }
        return delivered;
    }
}
