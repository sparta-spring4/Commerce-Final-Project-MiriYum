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
@Table(name = "store_reservation_schedule_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationScheduleVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_schedule_version_id")
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

    @Column(name = "validated_operating_version_id", nullable = false)
    private Long validatedOperatingVersionId;

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
            name = "store_reservation_schedule_entries",
            joinColumns = @JoinColumn(name = "reservation_schedule_version_id"))
    @OrderColumn(name = "entry_order")
    private List<ReservationScheduleEntry> entries = new ArrayList<>();

    private ReservationScheduleVersion(
            long storeId,
            long versionNumber,
            long validatedOperatingVersionId,
            String timeZoneId,
            List<ReservationScheduleEntry> entries
    ) {
        this.storeId = storeId;
        this.versionNumber = versionNumber;
        this.validatedOperatingVersionId = validatedOperatingVersionId;
        this.status = ScheduleVersionStatus.DRAFT;
        this.timeZoneId = timeZoneId;
        this.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
        this.entries = new ArrayList<>(entries);
    }

    public static ReservationScheduleVersion createDraft(
            long storeId,
            long versionNumber,
            long validatedOperatingVersionId,
            String timeZoneId,
            List<WeeklyInterval> intervals
    ) {
        List<ReservationScheduleEntry> entries = intervals.stream()
                .map(ReservationScheduleEntry::from)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return new ReservationScheduleVersion(
                storeId,
                versionNumber,
                validatedOperatingVersionId,
                timeZoneId,
                entries);
    }

    public static ReservationScheduleVersion create(
            long storeId,
            long versionNumber,
            long validatedOperatingVersionId,
            List<WeeklyInterval> intervals
    ) {
        ReservationScheduleVersion version = createDraft(
                storeId,
                versionNumber,
                validatedOperatingVersionId,
                "Asia/Seoul",
                intervals);
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
        status = ScheduleVersionStatus.ACTIVE;
        effectiveAt = activationTime;
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

    public List<ReservationScheduleEntry> getEntries() {
        return List.copyOf(entries);
    }
}
