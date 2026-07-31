package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StoreScheduleActivationJob {

    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;
    private final StoreScheduleService scheduleService;
    private final Clock clock;

    @Scheduled(fixedDelayString =
            "${miriyum.store.schedule.activation-delay-ms:1000}")
    public void activateDueSchedules() {
        Instant now = clock.instant();
        operatingRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now)
                .stream()
                .map(OperatingScheduleVersion::getId)
                .forEach(versionId -> activateOperatingSafely(versionId));
        reservationRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now)
                .stream()
                .map(ReservationScheduleVersion::getId)
                .forEach(versionId -> activateReservationSafely(versionId));
    }

    private void activateOperatingSafely(long versionId) {
        try {
            scheduleService.activateDueOperating(versionId);
        } catch (RuntimeException exception) {
            log.warn("Operating schedule activation failed. versionId={}",
                    versionId, exception);
        }
    }

    private void activateReservationSafely(long versionId) {
        try {
            scheduleService.activateDueReservation(versionId);
        } catch (RuntimeException exception) {
            log.warn("Reservation schedule activation failed. versionId={}",
                    versionId, exception);
        }
    }
}
