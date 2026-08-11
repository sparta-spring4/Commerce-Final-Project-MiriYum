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
    @Column(name = "reservation_id", nullable = false)
    private long reservationId;
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
    @Column(name = "status", nullable = false, length = 20)
    private MenuHoldStatus status;
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
     * 확정 홀드를 해제한다.
     *
     * @return 이번 호출이 상태를 전이했으면 {@code true}, 이미 해제 상태면 {@code false}
     * @throws IllegalStateException 이미 이행 완료된 홀드인 경우
     */
    public boolean release() {
        if (status == MenuHoldStatus.RELEASED) {
            return false;
        }
        if (status == MenuHoldStatus.FULFILLED) {
            throw new IllegalStateException("fulfilled menu hold cannot be released");
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
        if (status == MenuHoldStatus.FULFILLED) {
            return false;
        }
        if (status == MenuHoldStatus.RELEASED) {
            throw new IllegalStateException("released menu hold cannot be fulfilled");
        }
        status = MenuHoldStatus.FULFILLED;
        return true;
    }
}
