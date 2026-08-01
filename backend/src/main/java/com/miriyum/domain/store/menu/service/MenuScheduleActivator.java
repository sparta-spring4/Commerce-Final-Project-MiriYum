package com.miriyum.domain.store.menu.service;

import com.miriyum.domain.store.core.service.StoreScheduledActivationDecision;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.entity.Menu;
import com.miriyum.domain.store.menu.entity.MenuPublicationEvent;
import com.miriyum.domain.store.menu.entity.MenuVersion;
import com.miriyum.domain.store.menu.enums.MenuPublicationEventType;
import com.miriyum.domain.store.menu.enums.MenuAuditActorType;
import com.miriyum.domain.store.menu.enums.MenuAuditOutcome;
import com.miriyum.domain.store.menu.enums.MenuImpactCheckStatus;
import com.miriyum.domain.store.menu.enums.MenuRecoveryResult;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.MenuAuditRecord;
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

    private final StoreService storeService;
    private final MenuRepository menuRepository;
    private final MenuPublicationEventRepository eventRepository;
    private final MenuDatabaseClock databaseClock;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean activateDue(long menuId) {
        Long storeId = menuRepository.findStoreIdById(menuId).orElse(null);
        if (storeId == null) {
            return false;
        }
        StoreScheduledActivationDecision storeDecision =
                storeService.inspectScheduledActivation(storeId);
        if (!storeDecision.activationAllowed()) {
            return false;
        }
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
        Integer previousVersion = menu.getPublishedVersionNumber();
        MenuVisibility previousVisibility = menu.getVisibility();
        MenuSellingStatus previousSellingStatus = menu.getSellingStatus();
        MenuVersion activated = menu.activateScheduled(now);
        menuRepository.saveAndFlush(menu);
        eventRepository.save(MenuPublicationEvent.record(new MenuAuditRecord(
                menu.getId(),
                activated.getVersionNumber(),
                MenuPublicationEventType.SCHEDULE_ACTIVATED,
                MenuAuditActorType.SYSTEM,
                null,
                now,
                activated.getEffectiveAt(),
                now,
                "menu-schedule:" + menu.getId() + ":" + activated.getVersionNumber(),
                MenuAuditOutcome.SUCCEEDED,
                previousVersion,
                activated.getVersionNumber(),
                "VERSION_STATUS",
                "예약 게시 자동 활성화",
                previousVisibility,
                menu.getVisibility(),
                previousSellingStatus,
                menu.getSellingStatus(),
                MenuImpactCheckStatus.NOT_EVALUATED,
                null,
                MenuRecoveryResult.NOT_EVALUATED)));
        return true;
    }
}
