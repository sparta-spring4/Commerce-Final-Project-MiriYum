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

    public int getQuantity() {
        return quantity;
    }
}
