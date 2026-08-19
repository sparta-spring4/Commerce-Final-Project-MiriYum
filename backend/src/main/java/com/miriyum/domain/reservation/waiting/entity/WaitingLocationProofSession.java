package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 위치 원문 없이 웨이팅 등록용 위치 판정과 일회 소비 상태만 보존한다. */
@Entity
@Table(name = "waiting_location_proof_sessions")
public class WaitingLocationProofSession {

    public enum Purpose { WAITING_REGISTRATION }

    public enum ResultCategory {
        VERIFIED,
        OUTSIDE_RADIUS,
        ACCURACY_INSUFFICIENT,
        PERMISSION_DENIED,
        MEASUREMENT_STALE,
        POSITION_UNAVAILABLE,
        MANIPULATION_SUSPECTED
    }

    public enum AccuracyCategory { ACCEPTABLE, INSUFFICIENT, NOT_APPLICABLE }

    @Id
    @Column(name = "location_proof_session_id", length = 36)
    private String id;

    @Column(name = "consumer_account_id", nullable = false)
    private Long consumerAccountId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_category", nullable = false, length = 32)
    private ResultCategory resultCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "accuracy_category", nullable = false, length = 32)
    private AccuracyCategory accuracyCategory;

    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion;

    @Column(name = "store_coordinate_version", nullable = false)
    private long storeCoordinateVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "judged_at", nullable = false)
    private Instant judgedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_waiting_team_id")
    private Long consumedWaitingTeamId;

    protected WaitingLocationProofSession() {
    }

    private WaitingLocationProofSession(
            UUID id,
            long consumerAccountId,
            long storeId,
            Purpose purpose,
            ResultCategory resultCategory,
            AccuracyCategory accuracyCategory,
            String policyVersion,
            long storeCoordinateVersion,
            Instant judgedAt,
            Instant expiresAt
    ) {
        this.id = requireNonNull(id, "id").toString();
        this.consumerAccountId = requirePositive(consumerAccountId, "consumerAccountId");
        this.storeId = requirePositive(storeId, "storeId");
        this.purpose = requireNonNull(purpose, "purpose");
        this.resultCategory = requireNonNull(resultCategory, "resultCategory");
        this.accuracyCategory = requireNonNull(accuracyCategory, "accuracyCategory");
        this.policyVersion = requireText(policyVersion, 50, "policyVersion");
        if (storeCoordinateVersion < 0) {
            throw new IllegalArgumentException("storeCoordinateVersion must not be negative");
        }
        this.storeCoordinateVersion = storeCoordinateVersion;
        this.judgedAt = requireNonNull(judgedAt, "judgedAt");
        this.issuedAt = judgedAt;
        this.expiresAt = requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(judgedAt)) {
            throw new IllegalArgumentException("expiresAt must be after judgedAt");
        }
    }

    public static WaitingLocationProofSession issue(
            UUID id,
            long consumerAccountId,
            long storeId,
            Purpose purpose,
            ResultCategory resultCategory,
            AccuracyCategory accuracyCategory,
            String policyVersion,
            long storeCoordinateVersion,
            Instant judgedAt,
            Instant expiresAt
    ) {
        return new WaitingLocationProofSession(
                id, consumerAccountId, storeId, purpose, resultCategory, accuracyCategory,
                policyVersion, storeCoordinateVersion, judgedAt, expiresAt);
    }

    /** 결속, 통과, TTL, 일회성을 모두 검증한 뒤 등록 팀에 소비한다. */
    public void consume(
            long accountId,
            long requestedStoreId,
            Purpose requestedPurpose,
            long waitingTeamId,
            Instant now
    ) {
        requireConsumable(accountId, requestedStoreId, requestedPurpose, now);
        consumedWaitingTeamId = requirePositive(waitingTeamId, "waitingTeamId");
        consumedAt = now;
    }

    public void requireConsumable(
            long accountId,
            long requestedStoreId,
            Purpose requestedPurpose,
            Instant now
    ) {
        if (consumerAccountId != accountId
                || storeId != requestedStoreId
                || purpose != requestedPurpose
                || resultCategory != ResultCategory.VERIFIED
                || consumedAt != null
                || now == null
                || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("location proof is not consumable");
        }
    }

    public UUID getId() { return UUID.fromString(id); }
    public Long getConsumerAccountId() { return consumerAccountId; }
    public Long getStoreId() { return storeId; }
    public Purpose getPurpose() { return purpose; }
    public ResultCategory getResultCategory() { return resultCategory; }
    public AccuracyCategory getAccuracyCategory() { return accuracyCategory; }
    public String getPolicyVersion() { return policyVersion; }
    public long getStoreCoordinateVersion() { return storeCoordinateVersion; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getJudgedAt() { return judgedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Long getConsumedWaitingTeamId() { return consumedWaitingTeamId; }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requireText(String value, int maxLength, String name) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maxLength + " characters");
        }
        return value.trim();
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
