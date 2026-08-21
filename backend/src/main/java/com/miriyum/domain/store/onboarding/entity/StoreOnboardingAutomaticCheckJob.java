package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.AutomaticCheckStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "store_onboarding_automatic_check_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingAutomaticCheckJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_automatic_check_job_id")
    private Long id;

    @Column(name = "store_onboarding_application_id", nullable = false)
    private long storeOnboardingApplicationId;

    @Column(name = "application_version", nullable = false)
    private long applicationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AutomaticCheckStatus status;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_token", nullable = false)
    private long leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "result_code", length = 50)
    private String resultCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
}
