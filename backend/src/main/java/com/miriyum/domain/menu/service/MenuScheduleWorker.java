package com.miriyum.domain.menu.service;

import com.miriyum.domain.menu.repository.MenuRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MenuScheduleWorker {

    private final MenuRepository menuRepository;
    private final MenuDatabaseClock databaseClock;
    private final MenuScheduleActivator activator;
    private final int batchSize;

    public MenuScheduleWorker(
            MenuRepository menuRepository,
            MenuDatabaseClock databaseClock,
            MenuScheduleActivator activator,
            @Value("${miriyum.menu.schedule.batch-size:50}") int batchSize
    ) {
        this.menuRepository = menuRepository;
        this.databaseClock = databaseClock;
        this.activator = activator;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${miriyum.menu.schedule.fixed-delay-ms:5000}",
            initialDelayString = "${miriyum.menu.schedule.initial-delay-ms:5000}")
    public int activateDueBatch() {
        return (int) menuRepository.findDueScheduledIds(
                        databaseClock.now(), PageRequest.of(0, batchSize)).stream()
                .filter(activator::activateDue)
                .count();
    }
}
