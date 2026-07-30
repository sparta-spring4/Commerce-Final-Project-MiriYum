package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.store.menu.entity.MenuVersion;
import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.repository.MenuPublicationEventRepository;
import com.miriyum.domain.store.menu.repository.MenuRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MenuScheduleActivator {

    private final MenuRepository menuRepository;
    private final MenuPublicationEventRepository eventRepository;
    private final MenuDatabaseClock databaseClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean activateDue(long menuId) {
        Menu menu = menuRepository.findByIdForUpdate(menuId).orElse(null);
        if (menu == null || menu.isRetired() || menu.getScheduledVersionNumber() == null) {
            return false;
        }
        Instant now = databaseClock.now();
        MenuVersion candidate = menu.getVersions().stream()
                .filter(version ->
                        version.getVersionNumber() == menu.getScheduledVersionNumber())
                .findFirst()
                .orElse(null);
        if (candidate == null || candidate.getEffectiveAt() == null
                || candidate.getEffectiveAt().isAfter(now)) {
            return false;
        }
        MenuVersion activated = menu.activateScheduled(now);
        menuRepository.saveAndFlush(menu);
        eventRepository.save(MenuPublicationEvent.record(
                menu.getId(),
                activated.getVersionNumber(),
                MenuPublicationEventType.SCHEDULE_ACTIVATED,
                null,
                now,
                activated.getEffectiveAt(),
                now));
        return true;
    }
}
