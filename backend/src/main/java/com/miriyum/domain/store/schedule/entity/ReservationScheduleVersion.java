package com.miriyum.domain.store.schedule.entity;

import com.miriyum.domain.store.schedule.model.WeeklyInterval;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_schedule_version_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "version_number", nullable = false)
    private long versionNumber;

    @Column(name = "validated_operating_version_id", nullable = false)
    private Long validatedOperatingVersionId;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "store_reservation_schedule_entries",
            joinColumns = @JoinColumn(name = "reservation_schedule_version_id"))
    @OrderColumn(name = "entry_order")
    private List<ReservationScheduleEntry> entries = new ArrayList<>();

    public static ReservationScheduleVersion create(
            long storeId,
            long versionNumber,
            long validatedOperatingVersionId,
            List<WeeklyInterval> intervals
    ) {
        ReservationScheduleVersion version = new ReservationScheduleVersion();
        version.storeId = storeId;
        version.versionNumber = versionNumber;
        version.validatedOperatingVersionId = validatedOperatingVersionId;
        version.publishedAt = LocalDateTime.now(BUSINESS_ZONE);
        version.entries = intervals.stream()
                .map(ReservationScheduleEntry::from)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return version;
    }

    public List<ReservationScheduleEntry> getEntries() {
        return List.copyOf(entries);
    }
}
