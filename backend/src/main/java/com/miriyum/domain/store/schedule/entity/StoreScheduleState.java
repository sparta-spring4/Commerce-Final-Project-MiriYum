package com.miriyum.domain.store.schedule.entity;

import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_schedule_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreScheduleState extends BaseEntity {

    @Id
    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "active_operating_schedule_version_id")
    private Long activeOperatingScheduleVersionId;

    @Column(name = "active_reservation_schedule_version_id")
    private Long activeReservationScheduleVersionId;

    @Column(name = "active_regular_closure_version_id")
    private Long activeRegularClosureVersionId;

    @Column(name = "next_operating_version", nullable = false)
    private long nextOperatingVersion;

    @Column(name = "next_reservation_version", nullable = false)
    private long nextReservationVersion;

    @Column(name = "next_regular_closure_version", nullable = false)
    private long nextRegularClosureVersion;

    private StoreScheduleState(
            long storeId,
            long nextOperatingVersion,
            long nextReservationVersion
    ) {
        this.storeId = storeId;
        this.nextOperatingVersion = nextOperatingVersion;
        this.nextReservationVersion = nextReservationVersion;
        this.nextRegularClosureVersion = 1L;
    }

    public static StoreScheduleState initialize(long storeId) {
        return new StoreScheduleState(storeId, 1L, 1L);
    }

    public long allocateOperatingVersion() {
        return nextOperatingVersion++;
    }

    public long allocateReservationVersion() {
        return nextReservationVersion++;
    }

    public long allocateRegularClosureVersion() {
        return nextRegularClosureVersion++;
    }

    public void activateOperating(long versionId) {
        activeOperatingScheduleVersionId = versionId;
        activeReservationScheduleVersionId = null;
    }

    public void activateReservation(long versionId) {
        activeReservationScheduleVersionId = versionId;
    }

    public void activateRegularClosure(long versionId) {
        activeRegularClosureVersionId = versionId;
    }
}
