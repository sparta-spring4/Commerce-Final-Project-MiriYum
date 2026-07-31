package com.miriyum.domain.store.schedule.entity;

import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.schedule.model.ConflictCheckStatus;
import com.miriyum.domain.store.schedule.model.ScheduleVersionStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_operating_schedule_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperatingScheduleVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operating_schedule_version_id")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_check_status", nullable = false, length = 20)
    private ConflictCheckStatus conflictCheckStatus;

    @Column(name = "conflict_count")
    private Integer conflictCount;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "store_operating_schedule_entries",
            joinColumns = @JoinColumn(name = "operating_schedule_version_id"))
    @OrderColumn(name = "entry_order")
    private List<OperatingScheduleEntry> entries = new ArrayList<>();

    private OperatingScheduleVersion(
            long storeId,
            long versionNumber,
            String timeZoneId,
            List<OperatingScheduleEntry> entries
    ) {
        this.storeId = storeId;
        this.versionNumber = versionNumber;
        this.status = ScheduleVersionStatus.DRAFT;
        this.timeZoneId = timeZoneId;
        this.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
        this.entries = new ArrayList<>(entries);
    }

    public static OperatingScheduleVersion createDraft(
            long storeId,
            long versionNumber,
            String timeZoneId,
            List<WeeklyInterval> intervals
    ) {
        List<OperatingScheduleEntry> entries = intervals.stream()
                .map(OperatingScheduleEntry::from)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return new OperatingScheduleVersion(
                storeId,
                versionNumber,
                timeZoneId,
                entries);
    }

    public static OperatingScheduleVersion create(
            long storeId,
            long versionNumber,
            List<WeeklyInterval> intervals
    ) {
        OperatingScheduleVersion version =
                createDraft(storeId, versionNumber, "Asia/Seoul", intervals);
        version.activate(Instant.now(), "legacy immediate publication");
        return version;
    }

    public void schedule(Instant scheduledAt, String reason) {
        requireStatus(ScheduleVersionStatus.DRAFT);
        status = ScheduleVersionStatus.SCHEDULED;
        effectiveAt = scheduledAt;
        changeReason = reason;
    }

    public void activate(Instant activationTime, String reason) {
        if (status != ScheduleVersionStatus.DRAFT
                && status != ScheduleVersionStatus.SCHEDULED) {
            throw scheduleConflict();
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

    private void requireStatus(ScheduleVersionStatus expected) {
        if (status != expected) {
            throw scheduleConflict();
        }
    }

    private ServiceException scheduleConflict() {
        return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
    }

    public List<OperatingScheduleEntry> getEntries() {
        return List.copyOf(entries);
    }
}
