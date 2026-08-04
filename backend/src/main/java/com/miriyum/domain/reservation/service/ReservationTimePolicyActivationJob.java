package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "miriyum.reservation.time-policy.activation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class ReservationTimePolicyActivationJob {

    private static final PageRequest ACTIVATION_BATCH = PageRequest.of(0, 100);

    private final ReservationTimePolicyVersionRepository policyRepository;
    private final ReservationService reservationService;
    private final Clock clock;

    @Scheduled(fixedDelayString =
            "${miriyum.reservation.time-policy.activation-delay-ms:1000}")
    public void activateDuePolicies() {
        Instant now = clock.instant();
        policyRepository.findDueScheduledIds(
                        ReservationTimePolicyStatus.SCHEDULED,
                        now,
                        ACTIVATION_BATCH
                )
                .forEach(this::activateSafely);
    }

    private void activateSafely(long policyId) {
        try {
            reservationService.activateDueTimePolicy(policyId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Reservation time policy activation failed. policyId={}",
                    policyId,
                    exception
            );
        }
    }
}
