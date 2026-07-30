package com.miriyum.domain.store.core.entity;

import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.entity.BaseEntity;
import com.miriyum.global.exception.ServiceException;
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
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대표 운영자 한 명이 관리하는 매장 aggregate다.
 *
 * <p>운영자 계정은 다른 bounded context 소유이므로 JPA 연관관계 대신 검증된 계정 ID만 보관한다.</p>
 */
@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long id;

    @Column(name = "store_operator_account_id", nullable = false)
    private Long storeOperatorAccountId;

    @Column(name = "business_registration_number", nullable = false, length = 10)
    private String businessRegistrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 20)
    private BusinessType businessType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 20)
    private Region region;

    @Column(name = "address", nullable = false, length = 300)
    private String address;

    @Column(name = "store_category_code", nullable = false, length = 50)
    private String storeCategoryCode;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "store_tag_assignment",
            joinColumns = @JoinColumn(name = "store_id"))
    @Column(name = "tag_code", nullable = false, length = 50)
    private Set<String> tagCodes = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_status", nullable = false, length = 30)
    private OperationStatus operationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "pickup_eligibility", nullable = false, length = 20)
    private PickupEligibility pickupEligibility;

    @Column(name = "reservation_enabled", nullable = false)
    private boolean reservationEnabled;

    @Column(name = "menu_hold_enabled", nullable = false)
    private boolean menuHoldEnabled;

    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled;

    public static Store create(
            long storeOperatorAccountId,
            String businessRegistrationNumber,
            BusinessType businessType,
            String name,
            String description,
            Region region,
            String address,
            String storeCategoryCode,
            Set<String> tagCodes,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled
    ) {
        PickupEligibility eligibility = pickupEligibilityFor(businessType);
        requirePickupAllowed(eligibility, pickupEnabled);

        Store store = new Store();
        store.storeOperatorAccountId = storeOperatorAccountId;
        store.businessRegistrationNumber = businessRegistrationNumber;
        store.businessType = businessType;
        store.name = name;
        store.description = description;
        store.region = region;
        store.address = address;
        store.storeCategoryCode = storeCategoryCode;
        store.tagCodes = new LinkedHashSet<>(tagCodes);
        store.verificationStatus = VerificationStatus.APPROVED;
        store.operationStatus = OperationStatus.OPEN;
        store.pickupEligibility = eligibility;
        store.reservationEnabled = reservationEnabled;
        store.menuHoldEnabled = menuHoldEnabled;
        store.pickupEnabled = pickupEnabled;
        return store;
    }

    public void update(
            String name,
            String description,
            Region region,
            String address,
            String storeCategoryCode,
            Set<String> tagCodes,
            Boolean reservationEnabled,
            Boolean menuHoldEnabled,
            Boolean pickupEnabled,
            OperationStatus operationStatus
    ) {
        boolean nextPickupEnabled = pickupEnabled == null ? this.pickupEnabled : pickupEnabled;
        requirePickupAllowed(pickupEligibility, nextPickupEnabled);

        this.name = name == null ? this.name : name;
        this.description = description == null ? this.description : description;
        this.region = region == null ? this.region : region;
        this.address = address == null ? this.address : address;
        this.storeCategoryCode = storeCategoryCode == null ? this.storeCategoryCode : storeCategoryCode;
        this.tagCodes = tagCodes == null ? this.tagCodes : new LinkedHashSet<>(tagCodes);
        this.reservationEnabled = reservationEnabled == null ? this.reservationEnabled : reservationEnabled;
        this.menuHoldEnabled = menuHoldEnabled == null ? this.menuHoldEnabled : menuHoldEnabled;
        this.pickupEnabled = nextPickupEnabled;
        this.operationStatus = operationStatus == null ? this.operationStatus : operationStatus;
    }

    public void requireManagedBy(long operatorAccountId) {
        if (!storeOperatorAccountId.equals(operatorAccountId)) {
            throw new ServiceException(StoreErrorCode.ACCESS_DENIED);
        }
    }

    private static PickupEligibility pickupEligibilityFor(BusinessType businessType) {
        return businessType == BusinessType.OTHER
                ? PickupEligibility.INELIGIBLE
                : PickupEligibility.ELIGIBLE;
    }

    private static void requirePickupAllowed(PickupEligibility eligibility, boolean pickupEnabled) {
        if (eligibility == PickupEligibility.INELIGIBLE && pickupEnabled) {
            throw new ServiceException(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
        }
    }
}
