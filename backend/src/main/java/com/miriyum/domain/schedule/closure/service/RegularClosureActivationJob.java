package com.miriyum.domain.schedule.closure.service;

import com.miriyum.domain.schedule.closure.entity.RegularClosureVersion;
import com.miriyum.domain.schedule.closure.repository.RegularClosureVersionRepository;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RegularClosureActivationJob {
    private final RegularClosureVersionRepository repository;
    private final StoreClosureService service;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${miriyum.store.schedule.activation-delay-ms:1000}")
    public void activateDueClosures() {
        repository.findEarliestDuePerStore(ScheduleVersionStatus.SCHEDULED, clock.instant(), PageRequest.of(0, 100))
                .stream().map(RegularClosureVersion::getId).forEach(id -> {
                    try { service.activateDueRegular(id); }
                    catch (RuntimeException ex) { log.warn("Regular closure activation failed. versionId={}", id, ex); }
                });
    }
}
