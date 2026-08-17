package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.reservation.service.ReservationDepositCalculator.Calculation;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable Reservation-owned copy of every input used by PAY-002 calculation. */
@Embeddable
public class ReservationDepositCalculationSnapshot {

    @Column(name = "store_deposit_policy_version", nullable = false, updatable = false)
    private long storePolicyVersion;
    @Column(name = "deposit_rate_percent", nullable = false, updatable = false)
    private int ratePercent;
    @Column(name = "deposit_algorithm_version", nullable = false, updatable = false)
    private long algorithmVersion;
    @Column(name = "deposit_party_size", nullable = false, updatable = false)
    private int partySize;
    @Column(name = "deposit_amount_minor", nullable = false, updatable = false)
    private long amountMinor;
    @Column(name = "deposit_currency", nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(name = "representative_menu_version", nullable = false, updatable = false)
    private long representativeMenuVersion;
    @Column(name = "representative_menu_price_total", nullable = false, updatable = false)
    private long representativeMenuPriceTotal;
    @Column(name = "representative_menu_count", nullable = false, updatable = false)
    private int representativeMenuCount;

    @ElementCollection
    @CollectionTable(
            name = "reservation_deposit_calculation_items",
            joinColumns = @JoinColumn(name = "reservation_deposit_process_id"))
    private List<Item> items = new ArrayList<>();

    protected ReservationDepositCalculationSnapshot() {
    }

    public static ReservationDepositCalculationSnapshot copyOf(Calculation calculation) {
        if (calculation == null) {
            throw new IllegalArgumentException("calculation is required");
        }
        ReservationDepositCalculationSnapshot snapshot =
                new ReservationDepositCalculationSnapshot();
        snapshot.storePolicyVersion = calculation.storePolicyVersion();
        snapshot.ratePercent = calculation.ratePercent();
        snapshot.algorithmVersion = calculation.algorithmVersion();
        snapshot.partySize = calculation.partySize();
        snapshot.amountMinor = calculation.amountMinor();
        snapshot.currency = calculation.currency();
        snapshot.representativeMenuVersion = calculation.representativeMenuVersion();
        snapshot.representativeMenuPriceTotal = calculation.representativeMenuPriceTotal();
        snapshot.representativeMenuCount = calculation.representativeMenuCount();
        snapshot.items = calculation.items().stream()
                .map(item -> new Item(
                        item.menuId(), item.publishedVersionNumber(), item.price()))
                .toList();
        return snapshot;
    }

    public long getStorePolicyVersion() { return storePolicyVersion; }
    public int getRatePercent() { return ratePercent; }
    public long getAlgorithmVersion() { return algorithmVersion; }
    public int getPartySize() { return partySize; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public long getRepresentativeMenuVersion() { return representativeMenuVersion; }
    public long getRepresentativeMenuPriceTotal() { return representativeMenuPriceTotal; }
    public int getRepresentativeMenuCount() { return representativeMenuCount; }
    public List<Item> getItems() { return Collections.unmodifiableList(items); }

    @Embeddable
    public static class Item {
        @Column(name = "menu_id", nullable = false, updatable = false, length = 64)
        private String menuId;
        @Column(name = "published_version_number", nullable = false, updatable = false)
        private int publishedVersionNumber;
        @Column(name = "base_price", nullable = false, updatable = false)
        private int price;

        protected Item() {
        }

        private Item(String menuId, int publishedVersionNumber, int price) {
            this.menuId = menuId;
            this.publishedVersionNumber = publishedVersionNumber;
            this.price = price;
        }

        public String getMenuId() { return menuId; }
        public int getPublishedVersionNumber() { return publishedVersionNumber; }
        public int getPrice() { return price; }
    }
}
