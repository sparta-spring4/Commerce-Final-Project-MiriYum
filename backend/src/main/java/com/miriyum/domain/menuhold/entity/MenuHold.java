package com.miriyum.domain.menuhold.entity;

import com.miriyum.global.entity.BaseEntity;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuHold extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_hold_id")
    private Long id;
    @Column(name = "reservation_id")
    private Long reservationId;
    @Column(name = "reservation_hold_id")
    private Long reservationHoldId;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "store_id", nullable = false)
    private long storeId;
    @Column(name = "consumer_account_id", nullable = false)
    private long consumerAccountId;
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
    @Column(name = "acquire_operation_id", nullable = false, length = 100)
    private String acquireOperationId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MenuHoldStatus status;
    @Version
    @Column(name = "status_version", nullable = false)
    private long statusVersion;
    @OneToMany(mappedBy = "menuHold", cascade = CascadeType.ALL)
    private List<MenuHoldItem> items = new ArrayList<>();

    public static MenuHold confirmed(
            long reservationId, long storeId, long consumerAccountId,
            LocalDate serviceDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
            String acquireOperationId, List<MenuHoldItemSnapshot> snapshots
    ) {
        if (reservationId <= 0 || storeId <= 0 || consumerAccountId <= 0
                || serviceDate == null || startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime).isBefore(LocalDateTime.of(endDate, endTime))
                || acquireOperationId == null || acquireOperationId.isBlank()
                || acquireOperationId.length() > 100 || snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("invalid confirmed menu hold");
        }
        if (new HashSet<>(snapshots.stream()
                .map(MenuHoldItemSnapshot::menuInventoryBucketId).toList()).size()
                != snapshots.size()) {
            throw new IllegalArgumentException(
                    "menu hold items must have unique inventory buckets");
        }
        MenuHold hold = new MenuHold();
        hold.reservationId = reservationId;
        hold.storeId = storeId;
        hold.consumerAccountId = consumerAccountId;
        hold.serviceDate = serviceDate;
        hold.startTime = startTime;
        hold.endDate = endDate;
        hold.endTime = endTime;
        hold.acquireOperationId = acquireOperationId;
        hold.status = MenuHoldStatus.CONFIRMED;
        hold.items = snapshots.stream().map(snapshot -> MenuHoldItem.from(hold, snapshot)).toList();
        return hold;
    }

    /**
     * ReservationHold와 같은 만료 시각에 연결된 활성 임시 메뉴 홀드를 만든다.
     *
     * @param reservationHoldId 임시 선점 부모 ID
     * @param expiresAt 부모 ReservationHold와 동일한 중앙 만료 시각
     * @return 최종 Reservation에 아직 연결되지 않은 활성 임시 메뉴 홀드
     */
    public static MenuHold temporaryActive(
            long reservationHoldId, long storeId, long consumerAccountId,
            LocalDate serviceDate, LocalTime startTime, LocalDate endDate, LocalTime endTime,
            Instant expiresAt, String acquireOperationId, List<MenuHoldItemSnapshot> snapshots
    ) {
        if (reservationHoldId <= 0 || storeId <= 0 || consumerAccountId <= 0
                || serviceDate == null || startTime == null || endDate == null || endTime == null
                || !LocalDateTime.of(serviceDate, startTime).isBefore(LocalDateTime.of(endDate, endTime))
                || expiresAt == null || acquireOperationId == null || acquireOperationId.isBlank()
                || acquireOperationId.length() > 100 || snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("invalid temporary menu hold");
        }
        if (new HashSet<>(snapshots.stream()
                .map(MenuHoldItemSnapshot::menuInventoryBucketId).toList()).size()
                != snapshots.size()) {
            throw new IllegalArgumentException(
                    "menu hold items must have unique inventory buckets");
        }
        MenuHold hold = new MenuHold();
        hold.reservationHoldId = reservationHoldId;
        hold.storeId = storeId;
        hold.consumerAccountId = consumerAccountId;
        hold.serviceDate = serviceDate;
        hold.startTime = startTime;
        hold.endDate = endDate;
        hold.endTime = endTime;
        hold.expiresAt = expiresAt;
        hold.acquireOperationId = acquireOperationId;
        hold.status = MenuHoldStatus.ACTIVE;
        hold.items = snapshots.stream().map(snapshot -> MenuHoldItem.from(hold, snapshot)).toList();
        return hold;
    }

    /**
     * 활성 또는 대사 필요 임시 홀드를 기존 최종 Reservation에 연결해 확정한다.
     *
     * @param finalReservationId 이미 생성된 양의 최종 Reservation ID
     * @throws IllegalArgumentException 최종 Reservation ID가 양수가 아닌 경우
     * @throws IllegalStateException 임시 홀드가 아니거나 현재 상태에서 확정할 수 없는 경우
     */
    public void confirmTemporary(long finalReservationId) {
        requireTemporaryStatus(
                MenuHoldStatus.ACTIVE,
                MenuHoldStatus.RECONCILIATION_REQUIRED
        );
        if (finalReservationId <= 0) {
            throw new IllegalArgumentException("finalReservationId must be positive");
        }
        reservationId = finalReservationId;
        status = MenuHoldStatus.CONFIRMED;
    }

    /** 활성 임시 홀드의 수량을 유지한 채 대사 필요 상태로 격리한다. */
    public void requireTemporaryReconciliation() {
        requireTemporaryStatus(MenuHoldStatus.ACTIVE);
        status = MenuHoldStatus.RECONCILIATION_REQUIRED;
    }

    /** 활성 또는 대사 필요 임시 홀드를 해제 대상으로 전이한다. */
    public void releaseTemporary() {
        requireTemporaryStatus(
                MenuHoldStatus.ACTIVE,
                MenuHoldStatus.RECONCILIATION_REQUIRED
        );
        status = MenuHoldStatus.RELEASED;
    }

    /** 활성 임시 홀드를 만료 대상으로 전이한다. */
    public void expireTemporary() {
        requireTemporaryStatus(MenuHoldStatus.ACTIVE);
        status = MenuHoldStatus.EXPIRED;
    }

    /**
     * 확정 홀드를 해제한다.
     *
     * @return 이번 호출이 상태를 전이했으면 {@code true}, 이미 해제 상태면 {@code false}
     * @throws IllegalStateException 이미 이행 완료된 홀드인 경우
     */
    public boolean release() {
        requireFinalReservationMode();
        if (status == MenuHoldStatus.RELEASED) {
            return false;
        }
        if (status == MenuHoldStatus.FULFILLED) {
            throw new IllegalStateException("fulfilled menu hold cannot be released");
        }
        if (status == MenuHoldStatus.FORFEITED) {
            throw new IllegalStateException("forfeited menu hold cannot be released");
        }
        status = MenuHoldStatus.RELEASED;
        return true;
    }

    /**
     * 확정 홀드를 수량 복구 없이 이행 완료한다.
     *
     * @return 이번 호출이 상태를 전이했으면 {@code true}, 이미 이행 상태면 {@code false}
     * @throws IllegalStateException 이미 해제된 홀드인 경우
     */
    public boolean fulfill() {
        requireFinalReservationMode();
        if (status == MenuHoldStatus.FULFILLED) {
            return false;
        }
        if (status == MenuHoldStatus.RELEASED) {
            throw new IllegalStateException("released menu hold cannot be fulfilled");
        }
        if (status == MenuHoldStatus.FORFEITED) {
            throw new IllegalStateException("forfeited menu hold cannot be fulfilled");
        }
        status = MenuHoldStatus.FULFILLED;
        return true;
    }

    /**
     * 확정 홀드를 수량 복구 없이 노쇼 몰수 상태로 종결한다.
     *
     * @return 이번 호출이 상태를 전이했으면 {@code true}, 이미 몰수 상태면 {@code false}
     * @throws IllegalStateException 이미 해제 또는 이행 완료된 홀드인 경우
     */
    public boolean forfeit() {
        requireFinalReservationMode();
        if (status == MenuHoldStatus.FORFEITED) {
            return false;
        }
        if (status == MenuHoldStatus.RELEASED) {
            throw new IllegalStateException("released menu hold cannot be forfeited");
        }
        if (status == MenuHoldStatus.FULFILLED) {
            throw new IllegalStateException("fulfilled menu hold cannot be forfeited");
        }
        status = MenuHoldStatus.FORFEITED;
        return true;
    }

    private void requireFinalReservationMode() {
        boolean hasTemporaryLineage = reservationHoldId != null && expiresAt != null;
        boolean hasNoTemporaryLineage = reservationHoldId == null && expiresAt == null;
        if (reservationId == null
                || (!hasTemporaryLineage && !hasNoTemporaryLineage)
                || (status != MenuHoldStatus.CONFIRMED
                && status != MenuHoldStatus.RELEASED
                && status != MenuHoldStatus.FULFILLED
                && status != MenuHoldStatus.FORFEITED)) {
            throw new IllegalStateException(
                    "menu hold is not connected to a final reservation lifecycle");
        }
    }

    private void requireTemporaryStatus(MenuHoldStatus... allowedStatuses) {
        if (reservationHoldId == null || expiresAt == null) {
            throw new IllegalStateException("legacy menu hold cannot use a temporary transition");
        }
        for (MenuHoldStatus allowedStatus : allowedStatuses) {
            if (status == allowedStatus) {
                return;
            }
        }
        throw new IllegalStateException("invalid temporary menu hold transition");
    }
}
