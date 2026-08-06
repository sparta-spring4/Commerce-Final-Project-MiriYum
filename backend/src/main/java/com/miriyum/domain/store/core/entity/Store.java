package com.miriyum.domain.store.core.entity;

import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.OperationStatus;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

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

    @Column(name = "reservation_enabled", nullable = false)
    private boolean reservationEnabled;

    @Column(name = "menu_hold_enabled", nullable = false)
    private boolean menuHoldEnabled;

    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled;

    @Column(name = "applicant_self_attested_at", nullable = false)
    private LocalDateTime applicantSelfAttestedAt;

    @Column(name = "required_terms_agreed_at", nullable = false)
    private LocalDateTime requiredTermsAgreedAt;

    @Column(name = "required_terms_version", nullable = false, length = 50)
    private String requiredTermsVersion;

    private Store(
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
            boolean pickupEnabled,
            String timeZoneId,
            LocalDateTime onboardingAcceptedAt,
            String requiredTermsVersion
    ) {
        this.storeOperatorAccountId = storeOperatorAccountId;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.businessType = businessType;
        this.name = name;
        this.description = description;
        this.region = region;
        this.address = address;
        this.storeCategoryCode = storeCategoryCode;
        this.tagCodes = new LinkedHashSet<>(tagCodes);
        this.reservationEnabled = reservationEnabled;
        this.menuHoldEnabled = menuHoldEnabled;
        this.pickupEnabled = pickupEnabled;
        this.timeZoneId = timeZoneId;
        this.applicantSelfAttestedAt = onboardingAcceptedAt;
        this.requiredTermsAgreedAt = onboardingAcceptedAt;
        this.requiredTermsVersion = requiredTermsVersion;
    }

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
            boolean pickupEnabled,
            String timeZoneId,
            LocalDateTime onboardingAcceptedAt,
            String requiredTermsVersion
    ) {
        requireOnboardingEvidence(onboardingAcceptedAt, requiredTermsVersion);
        String canonicalTimeZoneId = requireTimeZone(timeZoneId);

        Store store = new Store(
                storeOperatorAccountId,
                businessRegistrationNumber,
                businessType,
                name,
                description,
                region,
                address,
                storeCategoryCode,
                tagCodes,
                reservationEnabled,
                menuHoldEnabled,
                pickupEnabled,
                canonicalTimeZoneId,
                onboardingAcceptedAt,
                requiredTermsVersion);
        store.verificationStatus = VerificationStatus.APPROVED;
        store.operationStatus = OperationStatus.OPEN;
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
        requireGeneralUpdateStatus(operationStatus);
        boolean nextPickupEnabled = pickupEnabled == null ? this.pickupEnabled : pickupEnabled;

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

    /**
     * 폐점 전용 유스케이스가 모든 선행 조건을 검증한 뒤 호출하는 비가역 전이다.
     */
    public void close() {
        this.operationStatus = OperationStatus.CLOSED;
    }

    public void requireManagedBy(long operatorAccountId) {
        if (!storeOperatorAccountId.equals(operatorAccountId)) {
            throw new ServiceException(StoreErrorCode.ACCESS_DENIED);
        }
    }

    private void requireGeneralUpdateStatus(OperationStatus requestedStatus) {
        if (requestedStatus == OperationStatus.CLOSED
                || (requestedStatus != null
                && operationStatus == OperationStatus.CLOSED)) {
            throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
        }
    }

    private static void requireOnboardingEvidence(
            LocalDateTime onboardingAcceptedAt,
            String requiredTermsVersion
    ) {
        if (onboardingAcceptedAt == null
                || requiredTermsVersion == null
                || requiredTermsVersion.isBlank()) {
            throw new IllegalArgumentException("onboarding evidence is required");
        }
    }

    private static String requireTimeZone(String timeZoneId) {
        if (timeZoneId == null || timeZoneId.isBlank()) {
            throw new IllegalArgumentException("store time zone is required");
        }
        if (!ZoneId.getAvailableZoneIds().contains(timeZoneId)) {
            throw new IllegalArgumentException(
                    "store time zone must be a valid IANA identifier");
        }
        return timeZoneId;
    }
}
