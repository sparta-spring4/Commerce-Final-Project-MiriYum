package com.miriyum.domain.pickup.entity;

import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 픽업 거래 스냅샷과 승인된 종결 전이를 소유한다. */
@Entity
@Table(name = "pickup_reservations")
public class PickupReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pickup_reservation_id")
    private Long id;

    @Column(name = "consumer_account_id", nullable = false)
    private long consumerAccountId;

    @Column(name = "store_id", nullable = false)
    private long storeId;

    @Column(name = "store_name_snapshot", nullable = false, length = 100)
    private String storeNameSnapshot;

    @Column(name = "time_zone_id_snapshot", nullable = false, length = 100)
    private String timeZoneIdSnapshot;

    @Column(name = "pickup_date", nullable = false)
    private LocalDate pickupDate;

    @Column(name = "pickup_time", nullable = false)
    private LocalTime pickupTime;

    @Column(name = "pickup_at", nullable = false)
    private Instant pickupAt;

    @Column(name = "acquire_operation_id", nullable = false, length = 100)
    private String acquireOperationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PickupStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private PickupCancellationActor cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @OneToMany(mappedBy = "pickupReservation", cascade = CascadeType.ALL)
    private List<PickupReservationItem> items = new ArrayList<>();

    protected PickupReservation() {
    }

    public static PickupReservation confirm(
            long consumerAccountId,
            long storeId,
            String storeNameSnapshot,
            String timeZoneIdSnapshot,
            LocalDate pickupDate,
            LocalTime pickupTime,
            Instant pickupAt,
            String acquireOperationId,
            List<PickupItemSnapshot> itemSnapshots,
            Instant createdAt
    ) {
        if (consumerAccountId <= 0 || storeId <= 0) {
            throw new IllegalArgumentException("pickup owner identifiers must be positive");
        }
        requireText(storeNameSnapshot, 100, "storeNameSnapshot", true);
        requireZoneId(timeZoneIdSnapshot);
        requireNonNull(pickupDate, "pickupDate");
        requireNonNull(pickupTime, "pickupTime");
        requireNonNull(pickupAt, "pickupAt");
        requireText(acquireOperationId, 100, "acquireOperationId", true);
        requireNonNull(createdAt, "createdAt");
        if (itemSnapshots == null || itemSnapshots.isEmpty()) {
            throw new IllegalArgumentException("pickup items must not be empty");
        }
        if (new HashSet<>(itemSnapshots.stream()
                .map(PickupItemSnapshot::menuInventoryBucketId)
                .toList()).size() != itemSnapshots.size()) {
            throw new IllegalArgumentException("pickup items must have unique inventory buckets");
        }

        PickupReservation reservation = new PickupReservation();
        reservation.consumerAccountId = consumerAccountId;
        reservation.storeId = storeId;
        reservation.storeNameSnapshot = storeNameSnapshot;
        reservation.timeZoneIdSnapshot = timeZoneIdSnapshot;
        reservation.pickupDate = pickupDate;
        reservation.pickupTime = pickupTime;
        reservation.pickupAt = pickupAt;
        reservation.acquireOperationId = acquireOperationId;
        reservation.status = PickupStatus.CONFIRMED;
        reservation.createdAt = createdAt;
        reservation.items = itemSnapshots.stream()
                .map(snapshot -> PickupReservationItem.from(reservation, snapshot))
                .toList();
        return reservation;
    }

    public void cancelByConsumer(String reason, Instant cancelledAt) {
        cancel(PickupCancellationActor.CONSUMER, reason, cancelledAt, false);
    }

    public void cancelByStoreOperator(String reason, Instant cancelledAt) {
        cancel(PickupCancellationActor.STORE_OPERATOR, reason, cancelledAt, true);
    }

    private void cancel(
            PickupCancellationActor actor,
            String reason,
            Instant cancelledAt,
            boolean reasonRequired
    ) {
        requireConfirmed();
        String validatedReason = requireText(reason, 500, "cancellationReason", reasonRequired);
        this.cancelledAt = requireTerminalTimestamp(cancelledAt, "cancelledAt");
        this.cancelledBy = actor;
        this.cancellationReason = validatedReason;
        this.status = PickupStatus.CANCELLED;
    }

    public void pickUp(Instant pickedUpAt) {
        requireConfirmed();
        this.pickedUpAt = requireTerminalTimestamp(pickedUpAt, "pickedUpAt");
        this.status = PickupStatus.PICKED_UP;
    }

    private void requireConfirmed() {
        if (status != PickupStatus.CONFIRMED) {
            throw new ServiceException(PickupErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private Instant requireTerminalTimestamp(Instant value, String fieldName) {
        Instant timestamp = requireNonNull(value, fieldName);
        if (timestamp.isBefore(createdAt)) {
            throw new IllegalArgumentException(fieldName + " must not be before createdAt");
        }
        return timestamp;
    }

    private static void requireZoneId(String value) {
        requireText(value, 100, "timeZoneIdSnapshot", true);
        try {
            ZoneId.of(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZoneIdSnapshot must be an IANA zone", exception);
        }
    }

    private static String requireText(
            String value,
            int maxLength,
            String fieldName,
            boolean required
    ) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(fieldName + " must not be null");
            }
            return null;
        }
        if (value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(fieldName + " has invalid length");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public Long getId() { return id; }
    public long getConsumerAccountId() { return consumerAccountId; }
    public long getStoreId() { return storeId; }
    public String getStoreNameSnapshot() { return storeNameSnapshot; }
    public String getTimeZoneIdSnapshot() { return timeZoneIdSnapshot; }
    public LocalDate getPickupDate() { return pickupDate; }
    public LocalTime getPickupTime() { return pickupTime; }
    public Instant getPickupAt() { return pickupAt; }
    public String getAcquireOperationId() { return acquireOperationId; }
    public PickupStatus getStatus() { return status; }
    public PickupCancellationActor getCancelledBy() { return cancelledBy; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public List<PickupReservationItem> getItems() { return List.copyOf(items); }
}
