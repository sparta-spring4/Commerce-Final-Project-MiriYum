package com.miriyum.domain.reservation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 전용 scheduler에서 만료 처리와 RECONCILIATION_REQUIRED 장기 체류 관측을 실행한다. */
@Component
@ConditionalOnProperty(
        name = "miriyum.reservation.hold-expiration.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Slf4j
public class ReservationHoldExpirationJob {

    private final ReservationHoldExpirationService expirationService;
    private final int batchSize;

    public ReservationHoldExpirationJob(
            ReservationHoldExpirationService expirationService,
            @Qualifier("reservationHoldExpirationBatchSize") Integer batchSize
    ) {
        this.expirationService = expirationService;
        if (batchSize == null || batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /** due ACTIVE 선점을 keyset batch로 만료한다. */
    @Scheduled(
            scheduler = "reservationHoldExpirationScheduler",
            fixedDelayString = "#{@reservationHoldExpirationPollDelayMs}"
    )
    public int expireDueHolds() {
        return expirationService.expireDueHolds(batchSize);
    }

    /** 현재 10분 장기 체류 그룹이 있으면 식별자 없는 level-triggered 신호를 남긴다. */
    @Scheduled(
            scheduler = "reservationHoldExpirationScheduler",
            fixedDelayString = "#{@reservationHoldReconciliationPollDelayMs}"
    )
    public long reportLongStayingReconciliations() {
        long longStayCount = expirationService.countLongStayingReconciliations();
        if (longStayCount > 0) {
            log.warn(
                    "event=reservation_hold_reconciliation_stalled long_stay_count={}",
                    longStayCount);
        }
        return longStayCount;
    }
}
