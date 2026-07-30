package com.miriyum.domain.store.menu.entity;

import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.model.MenuContent;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_version_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MenuVersionStatus status;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "representative", nullable = false)
    private boolean representative;

    @Column(name = "primary_category_code", nullable = false, length = 50)
    private String primaryCategoryCode;

    @ElementCollection
    @CollectionTable(
            name = "menu_version_secondary_categories",
            joinColumns = @JoinColumn(name = "menu_version_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "category_code", nullable = false, length = 50)
    private List<String> secondaryCategoryCodes = new ArrayList<>();

    @ElementCollection
    @CollectionTable(
            name = "menu_version_local_tags",
            joinColumns = @JoinColumn(name = "menu_version_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "tag_value", nullable = false, length = 30)
    private List<String> localTags = new ArrayList<>();

    @Column(name = "hold_selection_allowed", nullable = false)
    private boolean holdSelectionAllowed;

    @Column(name = "pickup_selection_allowed", nullable = false)
    private boolean pickupSelectionAllowed;

    @Column(name = "created_by_operator_id", nullable = false)
    private long createdByOperatorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    static MenuVersion draft(
            Menu menu,
            int versionNumber,
            MenuContent content,
            long operatorId,
            Instant now
    ) {
        MenuVersion version = new MenuVersion();
        version.menu = menu;
        version.versionNumber = versionNumber;
        version.status = MenuVersionStatus.DRAFT;
        version.name = content.name();
        version.description = content.description();
        version.price = content.price();
        version.representative = content.representative();
        version.primaryCategoryCode = content.primaryCategoryCode();
        version.secondaryCategoryCodes = new ArrayList<>(content.secondaryCategoryCodes());
        version.localTags = new ArrayList<>(content.localTags());
        version.holdSelectionAllowed = content.holdSelectionAllowed();
        version.pickupSelectionAllowed = content.pickupSelectionAllowed();
        version.createdByOperatorId = operatorId;
        version.createdAt = now;
        return version;
    }

    void schedule(Instant effectiveAt) {
        status = MenuVersionStatus.SCHEDULED;
        this.effectiveAt = effectiveAt;
    }

    void publish(Instant effectiveAt) {
        status = MenuVersionStatus.PUBLISHED;
        this.effectiveAt = effectiveAt;
    }

    void retire() {
        status = MenuVersionStatus.RETIRED;
    }
}
