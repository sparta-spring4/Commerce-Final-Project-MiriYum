package com.miriyum.domain.store.entity;

import com.miriyum.domain.store.enums.BusinessType;
import com.miriyum.domain.store.enums.GeocodingStatus;
import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import com.miriyum.domain.store.enums.VerificationStatus;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Objects;
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

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int COORDINATE_PRECISION = 18;
    private static final int COORDINATE_SCALE = 15;

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

    @Column(name = "address_version", nullable = false)
    private long addressVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "geocoding_status", nullable = false, length = 20)
    private GeocodingStatus geocodingStatus;

    @Column(name = "latitude", precision = 18, scale = 15)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 18, scale = 15)
    private BigDecimal longitude;

    @Column(name = "verified_address", length = 300)
    private String verifiedAddress;

    @Column(name = "geocoding_verified_at")
    private Instant geocodingVerifiedAt;

    @Column(name = "geocoding_address_version")
    private Long geocodingAddressVersion;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "dashboard_authority_version", nullable = false)
    private long dashboardAuthorityVersion;

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

    @Column(name = "platform_management_allowed", nullable = false)
    private boolean platformManagementAllowed;

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
        this.businessType = Objects.requireNonNull(businessType, "compatibility business type is required");
        this.name = name;
        this.description = description;
        this.region = region;
        this.address = address;
        this.addressVersion = 1L;
        this.geocodingStatus = GeocodingStatus.UNVERIFIED;
        this.storeCategoryCode = storeCategoryCode;
        this.tagCodes = new LinkedHashSet<>(tagCodes);
        this.reservationEnabled = reservationEnabled;
        this.menuHoldEnabled = menuHoldEnabled;
        this.pickupEnabled = pickupEnabled;
        this.platformManagementAllowed = true;
        this.timeZoneId = timeZoneId;
        this.dashboardAuthorityVersion = 1L;
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

    public static Store create(
            long storeOperatorAccountId,
            String businessRegistrationNumber,
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
        return create(
                storeOperatorAccountId, businessRegistrationNumber, BusinessType.OTHER,
                name, description, region, address, storeCategoryCode, tagCodes,
                reservationEnabled, menuHoldEnabled, pickupEnabled, timeZoneId,
                onboardingAcceptedAt, requiredTermsVersion);
    }

    /**
     * 주소 검증을 마친 신규 매장을 현재 주소 버전에 결합된 좌표와 함께 생성한다.
     *
     * @param geocoding 검증을 통과한 좌표와 최소 추적 정보
     * @return 좌표 검증 완료 상태의 신규 매장
     * @throws NullPointerException 검증 좌표의 필수 값이 누락된 경우
     */
    public static Store createVerified(
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
            String requiredTermsVersion,
            VerifiedStoreGeocoding geocoding
    ) {
        Store store = create(
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
                timeZoneId,
                onboardingAcceptedAt,
                requiredTermsVersion);
        store.applyVerifiedGeocoding(geocoding);
        return store;
    }

    public static Store createVerified(
            long storeOperatorAccountId,
            String businessRegistrationNumber,
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
            String requiredTermsVersion,
            VerifiedStoreGeocoding geocoding
    ) {
        return createVerified(
                storeOperatorAccountId, businessRegistrationNumber, BusinessType.OTHER,
                name, description, region, address, storeCategoryCode, tagCodes,
                reservationEnabled, menuHoldEnabled, pickupEnabled, timeZoneId,
                onboardingAcceptedAt, requiredTermsVersion, geocoding);
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
        update(
                name,
                description,
                region,
                address,
                storeCategoryCode,
                tagCodes,
                reservationEnabled,
                menuHoldEnabled,
                pickupEnabled,
                operationStatus,
                null);
    }

    /**
     * 일반 필드와 위치를 변경하며, 위치 변경 시 새 검증 좌표를 같은 주소 버전에 결합한다.
     *
     * @param geocoding 위치 변경에 대응하는 검증 좌표. 위치를 바꾸지 않으면 {@code null}
     * @throws IllegalArgumentException 위치 변경에 검증 좌표가 없는 경우
     */
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
            OperationStatus operationStatus,
            VerifiedStoreGeocoding geocoding
    ) {
        requireGeneralUpdateStatus(operationStatus);
        boolean nextPickupEnabled = pickupEnabled == null ? this.pickupEnabled : pickupEnabled;
        boolean locationTouched = region != null || address != null;
        if (locationTouched && geocoding == null) {
            throw new IllegalArgumentException(
                    "verified geocoding is required for a location update");
        }
        if (locationTouched) {
            requireVerifiedGeocoding(geocoding);
        }

        this.name = name == null ? this.name : name;
        this.description = description == null ? this.description : description;
        this.storeCategoryCode = storeCategoryCode == null ? this.storeCategoryCode : storeCategoryCode;
        this.tagCodes = tagCodes == null ? this.tagCodes : new LinkedHashSet<>(tagCodes);
        this.reservationEnabled = reservationEnabled == null ? this.reservationEnabled : reservationEnabled;
        this.menuHoldEnabled = menuHoldEnabled == null ? this.menuHoldEnabled : menuHoldEnabled;
        this.pickupEnabled = nextPickupEnabled;
        this.operationStatus = operationStatus == null ? this.operationStatus : operationStatus;
        if (locationTouched) {
            this.region = region == null ? this.region : region;
            this.address = address == null ? this.address : address;
            this.addressVersion++;
            applyVerifiedGeocoding(geocoding);
        }
    }

    private void applyVerifiedGeocoding(VerifiedStoreGeocoding geocoding) {
        VerifiedStoreGeocoding required = requireVerifiedGeocoding(geocoding);
        this.latitude = required.latitude();
        this.longitude = required.longitude();
        this.verifiedAddress = required.verifiedAddress();
        this.geocodingVerifiedAt = required.verifiedAt();
        this.geocodingAddressVersion = addressVersion;
        this.geocodingStatus = GeocodingStatus.VERIFIED;
    }

    private static VerifiedStoreGeocoding requireVerifiedGeocoding(
            VerifiedStoreGeocoding geocoding
    ) {
        VerifiedStoreGeocoding required = Objects.requireNonNull(
                geocoding,
                "verified geocoding is required");
        requireCoordinate(
                required.latitude(),
                MIN_LATITUDE,
                MAX_LATITUDE,
                "latitude");
        requireCoordinate(
                required.longitude(),
                MIN_LONGITUDE,
                MAX_LONGITUDE,
                "longitude");
        requireNonBlank(
                required.verifiedAddress(),
                "verified address is required");
        Objects.requireNonNull(
                required.verifiedAt(),
                "geocoding verified time is required");
        return required;
    }

    private static void requireCoordinate(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String fieldName
    ) {
        BigDecimal required = Objects.requireNonNull(
                value,
                fieldName + " is required");
        BigDecimal normalized = required.stripTrailingZeros();
        if (required.compareTo(minimum) < 0
                || required.compareTo(maximum) > 0
                || normalized.precision() > COORDINATE_PRECISION
                || normalized.scale() > COORDINATE_SCALE) {
            throw new IllegalArgumentException(
                    fieldName + " is outside the supported coordinate range or precision");
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /**
     * 폐점 전용 유스케이스가 모든 선행 조건을 검증한 뒤 호출하는 비가역 전이다.
     */
    public void close() {
        this.operationStatus = OperationStatus.CLOSED;
    }

    /** 플랫폼 제재 port가 잠금·version 검증 후 계산한 Store 정본 상태를 반영한다. */
    public void applyPlatformEnforcement(
            OperationStatus operationStatus,
            boolean reservationEnabled,
            boolean menuHoldEnabled,
            boolean pickupEnabled,
            boolean platformManagementAllowed
    ) {
        OperationStatus requiredOperationStatus = Objects.requireNonNull(
                operationStatus, "operation status is required");
        if (this.platformManagementAllowed != platformManagementAllowed) {
            this.dashboardAuthorityVersion = Math.addExact(this.dashboardAuthorityVersion, 1L);
        }
        this.operationStatus = requiredOperationStatus;
        this.reservationEnabled = reservationEnabled;
        this.menuHoldEnabled = menuHoldEnabled;
        this.pickupEnabled = pickupEnabled;
        this.platformManagementAllowed = platformManagementAllowed;
    }

    public void requirePlatformManagementAllowed() {
        if (!platformManagementAllowed) {
            throw new ServiceException(StoreErrorCode.STORE_FEATURE_RESTRICTED);
        }
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
