package com.miriyum.domain.pickup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "pickup_reservation_items")
public class PickupReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pickup_reservation_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pickup_reservation_id", nullable = false)
    private PickupReservation pickupReservation;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    @Column(name = "menu_inventory_bucket_id", nullable = false)
    private long menuInventoryBucketId;

    @Column(name = "menu_policy_version", nullable = false)
    private long menuPolicyVersion;

    @Column(name = "menu_name_snapshot", nullable = false, length = 100)
    private String menuNameSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false)
    private int unitPriceSnapshot;

    @Column(name = "inventory_policy_version", nullable = false)
    private long inventoryPolicyVersion;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected PickupReservationItem() {
    }

    static PickupReservationItem from(
            PickupReservation reservation,
            PickupItemSnapshot snapshot
    ) {
        PickupReservationItem item = new PickupReservationItem();
        item.pickupReservation = reservation;
        item.menuId = snapshot.menuId();
        item.menuInventoryBucketId = snapshot.menuInventoryBucketId();
        item.menuPolicyVersion = snapshot.menuPolicyVersion();
        item.menuNameSnapshot = snapshot.menuName();
        item.unitPriceSnapshot = snapshot.unitPrice();
        item.inventoryPolicyVersion = snapshot.inventoryPolicyVersion();
        item.serviceDate = snapshot.serviceDate();
        item.startTime = snapshot.startTime();
        item.endDate = snapshot.endDate();
        item.endTime = snapshot.endTime();
        item.quantity = snapshot.quantity();
        return item;
    }

    public Long getId() {
        return id;
    }

    public long getMenuId() {
        return menuId;
    }

    public long getMenuInventoryBucketId() {
        return menuInventoryBucketId;
    }

    public long getMenuPolicyVersion() {
        return menuPolicyVersion;
    }

    public String getMenuNameSnapshot() {
        return menuNameSnapshot;
    }

    public int getUnitPriceSnapshot() {
        return unitPriceSnapshot;
    }

    public long getInventoryPolicyVersion() {
        return inventoryPolicyVersion;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getQuantity() {
        return quantity;
    }
}
