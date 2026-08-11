package com.miriyum.domain.schedule.closure.entity;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.model.ScheduleVersionStatus;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_regular_closure_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegularClosureVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "regular_closure_version_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "version_number", nullable = false)
    private long versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ScheduleVersionStatus status;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "store_regular_closure_entries",
            joinColumns = @JoinColumn(name = "regular_closure_version_id"))
    @OrderColumn(name = "entry_order")
    private List<RegularClosureEntry> entries = new ArrayList<>();

    private RegularClosureVersion(
            long storeId,
            long versionNumber,
            String timeZoneId,
            List<RegularClosureEntry> entries
    ) {
        if (storeId <= 0 || versionNumber <= 0) {
            throw new IllegalArgumentException("closure owner and version must be positive");
        }
        this.storeId = storeId;
        this.versionNumber = versionNumber;
        this.timeZoneId = ZoneId.of(timeZoneId).getId();
        this.status = ScheduleVersionStatus.DRAFT;
        this.entries = new ArrayList<>(entries);
    }

    public static RegularClosureVersion createDraft(
            long storeId,
            long versionNumber,
            String timeZoneId,
            List<DayOfWeek> weeklyDays,
            List<LocalDate> dates
    ) {
        if (weeklyDays == null || dates == null
                || weeklyDays.stream().anyMatch(java.util.Objects::isNull)
                || dates.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("regular closure rules are required");
        }
        if (new HashSet<>(weeklyDays).size() != weeklyDays.size()
                || new HashSet<>(dates).size() != dates.size()) {
            throw new IllegalArgumentException("regular closure rules must be unique");
        }
        List<RegularClosureEntry> entries = new ArrayList<>();
        weeklyDays.stream().map(RegularClosureEntry::weekly).forEach(entries::add);
        dates.stream().map(RegularClosureEntry::date).forEach(entries::add);
        return new RegularClosureVersion(storeId, versionNumber, timeZoneId, entries);
    }

    public void schedule(Instant scheduledAt, String reason) {
        requireStatus(ScheduleVersionStatus.DRAFT);
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduled time is required");
        }
        status = ScheduleVersionStatus.SCHEDULED;
        effectiveAt = scheduledAt;
        changeReason = reason;
    }

    public void activate(Instant activationTime, String reason) {
        if (status != ScheduleVersionStatus.DRAFT
                && status != ScheduleVersionStatus.SCHEDULED) {
            throw conflict();
        }
        if (status == ScheduleVersionStatus.DRAFT) {
            effectiveAt = activationTime;
        }
        status = ScheduleVersionStatus.ACTIVE;
        activatedAt = activationTime;
        changeReason = reason;
    }

    public void cancelPublication() {
        requireStatus(ScheduleVersionStatus.SCHEDULED);
        status = ScheduleVersionStatus.DRAFT;
        effectiveAt = null;
        changeReason = null;
    }

    public void retire() {
        requireStatus(ScheduleVersionStatus.ACTIVE);
        status = ScheduleVersionStatus.RETIRED;
    }

    public void failActivation() {
        requireStatus(ScheduleVersionStatus.SCHEDULED);
        status = ScheduleVersionStatus.ACTIVATION_FAILED;
    }

    public boolean isClosedOn(LocalDate date) {
        return entries.stream().anyMatch(entry -> entry.matches(date));
    }

    public List<RegularClosureEntry> getEntries() {
        return List.copyOf(entries);
    }

    private void requireStatus(ScheduleVersionStatus expected) {
        if (status != expected) {
            throw conflict();
        }
    }

    private ServiceException conflict() {
        return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
    }
}
