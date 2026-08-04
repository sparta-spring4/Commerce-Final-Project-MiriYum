package com.miriyum.domain.menuhold.entity;

import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_hold_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuHoldItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_hold_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_hold_id", nullable = false)
    private MenuHold menuHold;

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

    static MenuHoldItem from(MenuHold hold, MenuHoldItemSnapshot snapshot) {
        MenuHoldItem item = new MenuHoldItem();
        item.menuHold = hold;
        item.menuId = snapshot.menuId();
        item.menuInventoryBucketId = snapshot.menuInventoryBucketId();
        item.menuPolicyVersion = snapshot.menuPolicyVersion();
        item.menuNameSnapshot = snapshot.menuName();
        item.unitPriceSnapshot = snapshot.unitPrice();
        item.inventoryPolicyVersion = snapshot.inventoryPolicyVersion();
        item.quantity = snapshot.quantity();
        return item;
    }
}
