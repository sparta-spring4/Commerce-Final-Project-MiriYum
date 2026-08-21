package com.miriyum.domain.store.onboarding.entity;

import com.miriyum.domain.store.onboarding.entity.StoreOnboardingEnums.DecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only terminal or request-changes decision ledger. */
@Entity
@Table(name = "store_onboarding_decisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreOnboardingDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_onboarding_decision_id")
    private Long id;

    @Column(name = "decision_public_id", nullable = false, length = 36, unique = true)
    private String decisionPublicId;

    @Column(name = "case_public_id", nullable = false, length = 36)
    private String casePublicId;

    @Column(name = "case_version", nullable = false)
    private long caseVersion;

    @Column(name = "decided_by_platform_operator_id", nullable = false)
    private long decidedByPlatformOperatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 30)
    private DecisionType decisionType;

    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    @Column(name = "reason_detail", length = 1000)
    private String reasonDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static StoreOnboardingDecision record(
            String casePublicId,
            long caseVersion,
            long operatorId,
            DecisionType type,
            String reasonCode,
            String reasonDetail,
            Instant now
    ) {
        StoreOnboardingDecision decision = new StoreOnboardingDecision();
        decision.decisionPublicId = UUID.randomUUID().toString();
        decision.casePublicId = requireText(casePublicId, "case public id");
        if (caseVersion <= 0 || operatorId <= 0) {
            throw new IllegalArgumentException("case version and operator id must be positive");
        }
        decision.caseVersion = caseVersion;
        decision.decidedByPlatformOperatorId = operatorId;
        decision.decisionType = java.util.Objects.requireNonNull(type, "decision type is required");
        decision.reasonCode = requireText(reasonCode, "reason code");
        decision.reasonDetail = reasonDetail;
        decision.createdAt = java.util.Objects.requireNonNull(now, "created at is required");
        return decision;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
