package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.enums.BusinessType;
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

    @Lob
    @Column(name = "tag_codes_json", nullable = false, columnDefinition = "JSON")
    private String tagCodesJson;

    @Column(name = "reservation_enabled", nullable = false)
    private boolean reservationEnabled;

    @Column(name = "menu_hold_enabled", nullable = false)
    private boolean menuHoldEnabled;

    @Column(name = "pickup_enabled", nullable = false)
    private boolean pickupEnabled;

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
}
