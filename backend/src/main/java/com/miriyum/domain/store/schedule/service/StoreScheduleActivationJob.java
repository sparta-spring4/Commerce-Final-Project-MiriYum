package com.miriyum.domain.store.schedule.service;

import com.miriyum.domain.store.schedule.entity.OperatingScheduleVersion;
import com.miriyum.domain.store.schedule.entity.ReservationScheduleVersion;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
import com.miriyum.domain.store.schedule.repository.OperatingScheduleVersionRepository;
import com.miriyum.domain.store.schedule.repository.ReservationScheduleVersionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
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
                .forEach(scheduleService::activateDueOperating);
        reservationRepository
                .findTop100ByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAscVersionNumberAsc(
                        ScheduleVersionStatus.SCHEDULED,
                        now)
                .stream()
                .map(ReservationScheduleVersion::getId)
                .forEach(scheduleService::activateDueReservation);
    }
}
