package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.enums.Region;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import com.miriyum.domain.store.dto.storeoperator.StoreCreateRequest;
import com.miriyum.domain.store.model.VerifiedStoreGeocoding;
import java.math.BigDecimal;
import tools.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable snapshot used to create a Store only after the workflow approves it. */
@Entity
@Table(name = "store_onboarding_application_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingApplicationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_application_version_id")
    private Long id;

    @Column(name = "store_onboarding_application_id", nullable = false)
    private long storeOnboardingApplicationId;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    @Column(name = "supplement_idempotency_key", length = 100)
    private String supplementIdempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "business_registration_evidence_id", nullable = false, length = 36)
    private String businessRegistrationEvidenceId;

    @Column(name = "business_registration_number", nullable = false, length = 10)
    private String businessRegistrationNumber;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 20)
    private Region region;

    @Column(name = "address", nullable = false, length = 300)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 18, scale = 15)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 18, scale = 15)
    private BigDecimal longitude;

    @Column(name = "verified_address", nullable = false, length = 300)
    private String verifiedAddress;

    @Column(name = "geocoding_verified_at", nullable = false)
    private Instant geocodingVerifiedAt;

    @Column(name = "time_zone_id", nullable = false, length = 64)
    private String timeZoneId;

    @Column(name = "store_category_code", nullable = false, length = 50)
    private String storeCategoryCode;

    @Lob
    @Column(name = "tag_codes_json", nullable = false, columnDefinition = "JSON")
    private String tagCodesJson;

    @Column(name = "reservation_enabled", nullable = false)
    private boolean reservationEnabled;

    @Column(name = "menu_hold_enabled", nullable = false)
    private boolean menuHoldEnabled;

    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled;

    @Column(name = "legal_business_name", nullable = false, length = 200)
    private String legalBusinessName;

    @Column(name = "representative_name", nullable = false, length = 100)
    private String representativeName;

    @Column(name = "opening_date", nullable = false)
    private LocalDate openingDate;

    @Column(name = "primary_business_category", nullable = false, length = 100)
    private String primaryBusinessCategory;

    @Column(name = "primary_business_item", nullable = false, length = 100)
    private String primaryBusinessItem;

    @Column(name = "applicant_self_attested_at", nullable = false)
    private Instant applicantSelfAttestedAt;

    @Column(name = "required_terms_agreed_at", nullable = false)
    private Instant requiredTermsAgreedAt;

    @Column(name = "required_terms_version", nullable = false, length = 50)
    private String requiredTermsVersion;

    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired;

    @Column(name = "automatic_check_policy_version", nullable = false, length = 50)
    private String automaticCheckPolicyVersion;

    @Column(name = "manual_review_policy_version", nullable = false, length = 50)
    private String manualReviewPolicyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static StoreOnboardingApplicationVersion snapshot(
            long applicationId,
            long version,
            String supplementKey,
            String fingerprint,
            String evidenceId,
            StoreCreateRequest request,
            VerifiedStoreGeocoding geocoding,
            boolean reviewRequired,
            String automaticPolicyVersion,
            String manualPolicyVersion,
            Instant now,
            ObjectMapper objectMapper
    ) {
        StoreOnboardingApplicationVersion snapshot = new StoreOnboardingApplicationVersion();
        snapshot.storeOnboardingApplicationId = applicationId;
        snapshot.applicationVersion = version;
        snapshot.supplementIdempotencyKey = supplementKey;
        snapshot.requestFingerprint = fingerprint;
        snapshot.businessRegistrationEvidenceId = evidenceId;
        snapshot.businessRegistrationNumber = request.businessRegistrationNumber();
        snapshot.name = request.name();
        snapshot.description = request.description();
        snapshot.region = request.region();
        snapshot.address = request.address();
        snapshot.latitude = geocoding.latitude();
        snapshot.longitude = geocoding.longitude();
        snapshot.verifiedAddress = geocoding.verifiedAddress();
        snapshot.geocodingVerifiedAt = geocoding.verifiedAt();
        snapshot.timeZoneId = request.timeZoneId();
        snapshot.storeCategoryCode = request.storeCategoryCode();
        snapshot.tagCodesJson = objectMapper.writeValueAsString(request.tagCodes());
        snapshot.reservationEnabled = request.modes().reservationEnabled();
        snapshot.menuHoldEnabled = request.modes().menuHoldEnabled();
        snapshot.pickupEnabled = request.modes().pickupEnabled();
        snapshot.legalBusinessName = request.legalBusinessName();
        snapshot.representativeName = request.representativeName();
        snapshot.openingDate = request.openingDate();
        snapshot.primaryBusinessCategory = request.primaryBusinessCategory();
        snapshot.primaryBusinessItem = request.primaryBusinessItem();
        snapshot.applicantSelfAttestedAt = now;
        snapshot.requiredTermsAgreedAt = now;
        snapshot.requiredTermsVersion = "STORE_ONBOARDING_REQUIRED_TERMS_V1";
        snapshot.reviewRequired = reviewRequired;
        snapshot.automaticCheckPolicyVersion = automaticPolicyVersion;
        snapshot.manualReviewPolicyVersion = manualPolicyVersion;
        snapshot.createdAt = now;
        return snapshot;
    }
}
