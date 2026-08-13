package com.miriyum.domain.menu.entity;

import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "representative_menu_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepresentativeMenuSetting extends BaseEntity {

    @Id
    @Column(name = "store_id")
    private long storeId;

    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RepresentativeMenuSettingStatus status;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @OneToMany(mappedBy = "setting", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id.displayOrder ASC")
    private List<RepresentativeMenuEntry> entries = new ArrayList<>();

    private RepresentativeMenuSetting(long storeId) {
        if (storeId <= 0) {
            throw new IllegalArgumentException("storeId must be positive");
        }
        this.storeId = storeId;
        this.status = RepresentativeMenuSettingStatus.UNCONFIGURED;
    }

    public static RepresentativeMenuSetting create(long storeId) {
        return new RepresentativeMenuSetting(storeId);
    }

    public void replace(List<Long> orderedMenuIds) {
        validateReplacement(orderedMenuIds);
        entries.clear();
        for (int index = 0; index < orderedMenuIds.size(); index++) {
            entries.add(new RepresentativeMenuEntry(this, index + 1, orderedMenuIds.get(index)));
        }
        version++;
        status = RepresentativeMenuSettingStatus.CONFIGURED;
    }

    public boolean remove(long menuId) {
        boolean removed = entries.removeIf(entry -> entry.getMenuId() == menuId);
        if (!removed) {
            return false;
        }
        List<Long> remaining = orderedMenuIds();
        version++;
        status = remaining.size() >= 3
                ? RepresentativeMenuSettingStatus.CONFIGURED
                : RepresentativeMenuSettingStatus.REQUIRES_ATTENTION;
        return true;
    }

    public List<Long> orderedMenuIds() {
        return Collections.unmodifiableList(entries.stream()
                .map(RepresentativeMenuEntry::getMenuId)
                .toList());
    }

    private static void validateReplacement(List<Long> orderedMenuIds) {
        if (orderedMenuIds == null || orderedMenuIds.size() < 3 || orderedMenuIds.size() > 5) {
            throw new IllegalArgumentException("representative menus must contain 3 to 5 items");
        }
        if (orderedMenuIds.stream().anyMatch(menuId -> menuId == null || menuId <= 0)
                || new HashSet<>(orderedMenuIds).size() != orderedMenuIds.size()) {
            throw new IllegalArgumentException("representative menu ids must be distinct and positive");
        }
    }
}
