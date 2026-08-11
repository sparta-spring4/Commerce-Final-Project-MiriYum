package com.miriyum.domain.schedule.service;

import com.miriyum.domain.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.schedule.repository.ReservationScheduleVersionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StoreScheduleActivationJob {

    private static final PageRequest ACTIVATION_BATCH = PageRequest.of(0, 100);

    private final OperatingScheduleVersionRepository operatingRepository;
    private final ReservationScheduleVersionRepository reservationRepository;
    private final StoreScheduleService scheduleService;
    private final Clock clock;

    @Scheduled(fixedDelayString =
            "${miriyum.store.schedule.activation-delay-ms:1000}")
    public void activateDueSchedules() {
        Instant now = clock.instant();
        operatingRepository
                .findEarliestDuePerStore(
                        ScheduleVersionStatus.SCHEDULED,
                        now,
                        ACTIVATION_BATCH)
                .stream()
                .map(OperatingScheduleVersion::getId)
                .forEach(versionId -> activateOperatingSafely(versionId));
        reservationRepository
                .findEarliestDuePerStore(
                        ScheduleVersionStatus.SCHEDULED,
                        now,
                        ACTIVATION_BATCH)
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
