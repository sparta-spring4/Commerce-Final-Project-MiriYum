package com.miriyum.domain.store.menu.entity;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.enums.MenuSellingStatus;
import com.miriyum.domain.store.menu.enums.MenuVisibility;
import com.miriyum.domain.store.menu.model.MenuContent;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "next_version_number", nullable = false)
    private int nextVersionNumber;

    @Column(name = "draft_version_number")
    private Integer draftVersionNumber;

    @Column(name = "scheduled_version_number")
    private Integer scheduledVersionNumber;

    @Column(name = "published_version_number")
    private Integer publishedVersionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private MenuVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_status", nullable = false, length = 20)
    private MenuSellingStatus sellingStatus;

    @Column(name = "retired", nullable = false)
    private boolean retired;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "menu", cascade = CascadeType.ALL, orphanRemoval = false,
            fetch = FetchType.LAZY)
    @OrderBy("versionNumber ASC")
    private List<MenuVersion> versions = new ArrayList<>();

    private Menu(long storeId) {
        this.storeId = storeId;
        this.nextVersionNumber = 1;
        this.visibility = MenuVisibility.HIDDEN;
        this.sellingStatus = MenuSellingStatus.PAUSED;
    }

    public static Menu create(
            long storeId,
            MenuContent content,
            long operatorId,
            Instant now
    ) {
        Menu menu = new Menu(storeId);
        menu.appendDraft(content, operatorId, now);
        return menu;
    }

    public MenuVersion appendDraft(MenuContent content, long operatorId, Instant now) {
        requireActive();
        findVersion(draftVersionNumber).ifPresent(MenuVersion::retire);
        int versionNumber = nextVersionNumber++;
        MenuVersion version = MenuVersion.draft(this, versionNumber, content, operatorId, now);
        versions.add(version);
        draftVersionNumber = versionNumber;
        return version;
    }

    public MenuVersion requireDraft() {
        return findVersion(draftVersionNumber).orElseThrow(Menu::stateConflict);
    }

    public MenuVersion publish(Instant now) {
        requireActive();
        MenuVersion draft = requireDraft();
        draft.requirePublishableDisclosures();
        findVersion(publishedVersionNumber).ifPresent(MenuVersion::retire);
        findVersion(scheduledVersionNumber).ifPresent(MenuVersion::retire);
        boolean firstPublication = publishedVersionNumber == null;
        draft.publish(now);
        publishedVersionNumber = draft.getVersionNumber();
        draftVersionNumber = null;
        scheduledVersionNumber = null;
        if (firstPublication) {
            visibility = MenuVisibility.VISIBLE;
            sellingStatus = MenuSellingStatus.SELLING;
        }
        return draft;
    }

    public MenuVersion schedule(Instant effectiveAt, Instant now) {
        requireActive();
        if (!effectiveAt.isAfter(now)) {
            throw stateConflict();
        }
        MenuVersion draft = requireDraft();
        draft.requirePublishableDisclosures();
        findVersion(scheduledVersionNumber).ifPresent(MenuVersion::retire);
        draft.schedule(effectiveAt);
        scheduledVersionNumber = draft.getVersionNumber();
        draftVersionNumber = null;
        return draft;
    }

    public MenuVersion activateScheduled(Instant now) {
        requireActive();
        MenuVersion scheduled = findVersion(scheduledVersionNumber)
                .orElseThrow(Menu::stateConflict);
        if (scheduled.getEffectiveAt() == null || scheduled.getEffectiveAt().isAfter(now)) {
            throw stateConflict();
        }
        findVersion(publishedVersionNumber).ifPresent(MenuVersion::retire);
        boolean firstPublication = publishedVersionNumber == null;
        scheduled.publish(scheduled.getEffectiveAt());
        publishedVersionNumber = scheduled.getVersionNumber();
        scheduledVersionNumber = null;
        if (firstPublication) {
            visibility = MenuVisibility.VISIBLE;
            sellingStatus = MenuSellingStatus.SELLING;
        }
        return scheduled;
    }

    public MenuVersion cancelSchedule(Instant now) {
        requireActive();
        MenuVersion scheduled = findVersion(scheduledVersionNumber)
                .orElseThrow(Menu::stateConflict);
        if (scheduled.getEffectiveAt() == null || !now.isBefore(scheduled.getEffectiveAt())) {
            throw stateConflict();
        }
        if (draftVersionNumber != null) {
            throw stateConflict();
        }
        scheduled.cancelSchedule();
        draftVersionNumber = scheduled.getVersionNumber();
        scheduledVersionNumber = null;
        return scheduled;
    }

    public void changeVisibility(MenuVisibility visibility) {
        requireActive();
        if (publishedVersionNumber == null) {
            throw stateConflict();
        }
        this.visibility = visibility;
    }

    public void changeSellingStatus(MenuSellingStatus sellingStatus) {
        requireActive();
        if (publishedVersionNumber == null) {
            throw stateConflict();
        }
        this.sellingStatus = sellingStatus;
    }

    public void retire(Instant now) {
        requireActive();
        versions.stream()
                .filter(version -> version.getStatus()
                        != com.miriyum.domain.store.menu.enums.MenuVersionStatus.RETIRED)
                .forEach(MenuVersion::retire);
        draftVersionNumber = null;
        scheduledVersionNumber = null;
        publishedVersionNumber = null;
        visibility = MenuVisibility.HIDDEN;
        sellingStatus = MenuSellingStatus.PAUSED;
        retired = true;
    }

    public List<MenuVersion> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    private java.util.Optional<MenuVersion> findVersion(Integer versionNumber) {
        if (versionNumber == null) {
            return java.util.Optional.empty();
        }
        return versions.stream()
                .filter(version -> version.getVersionNumber() == versionNumber)
                .findFirst();
    }

    private void requireActive() {
        if (retired) {
            throw stateConflict();
        }
    }

    private static ServiceException stateConflict() {
        return new ServiceException(StoreErrorCode.MENU_STATE_CONFLICT);
    }
}
