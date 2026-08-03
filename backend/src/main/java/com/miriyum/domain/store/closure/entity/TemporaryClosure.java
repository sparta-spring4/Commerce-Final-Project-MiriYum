package com.miriyum.domain.store.closure.entity;

import com.miriyum.domain.store.closure.model.TemporaryClosureReason;
import com.miriyum.domain.store.closure.model.TemporaryClosureStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_temporary_closures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemporaryClosure extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "temporary_closure_id")
    private Long id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private TemporaryClosureReason reason;

    @Column(name = "public_message", length = 200)
    private String publicMessage;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "change_version", nullable = false)
    private long changeVersion;

    private TemporaryClosure(
            long storeId,
            Instant startAt,
            Instant endAt,
            String timeZoneId,
            TemporaryClosureReason reason,
            String publicMessage
    ) {
        this.storeId = storeId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.timeZoneId = ZoneId.of(timeZoneId).getId();
        this.reason = reason;
        this.publicMessage = normalizeMessage(publicMessage);
        this.changeVersion = 1L;
    }

    public static TemporaryClosure create(
            long storeId,
            Instant startAt,
            Instant endAt,
            String timeZoneId,
            TemporaryClosureReason reason,
            String publicMessage
    ) {
        if (storeId <= 0 || startAt == null || endAt == null
                || !startAt.isBefore(endAt) || reason == null) {
            throw new IllegalArgumentException("invalid temporary closure");
        }
        return new TemporaryClosure(
                storeId, startAt, endAt, timeZoneId, reason, publicMessage);
    }

    public TemporaryClosureStatus statusAt(Instant now) {
        if (cancelledAt != null) {
            return TemporaryClosureStatus.CANCELLED;
        }
        if (now.isBefore(startAt)) {
            return TemporaryClosureStatus.SCHEDULED;
        }
        return now.isBefore(endAt)
                ? TemporaryClosureStatus.ACTIVE
                : TemporaryClosureStatus.ENDED;
    }

    public boolean overlaps(Instant intervalStart, Instant intervalEnd) {
        return startAt.isBefore(intervalEnd) && intervalStart.isBefore(endAt);
    }

    public void changeEndAt(Instant changedEndAt, Instant now) {
        if (cancelledAt != null || !now.isBefore(endAt)
                || changedEndAt == null || !now.isBefore(changedEndAt)
                || !startAt.isBefore(changedEndAt)) {
            throw conflict();
        }
        endAt = changedEndAt;
        changeVersion++;
    }

    public void cancel(Instant now) {
        if (cancelledAt != null || !now.isBefore(endAt)) {
            throw conflict();
        }
        cancelledAt = now;
        changeVersion++;
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("temporary closure message is too long");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private ServiceException conflict() {
        return new ServiceException(StoreErrorCode.SCHEDULE_CONFLICT);
    }
}
