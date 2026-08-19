package com.miriyum.domain.reservation.waiting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** 수락 전에는 대표자를 바꾸지 않는 대표자 이전 제안 원장이다. */
@Entity
@Table(name = "waiting_representative_transfer_offers", uniqueConstraints = @UniqueConstraint(
        name = "uk_waiting_transfer_active_team", columnNames = "active_team_key"))
public class WaitingRepresentativeTransferOffer {

    public enum Status { PROPOSED, ACCEPTED, REJECTED, REVOKED, EXPIRED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_representative_transfer_offer_id")
    private Long id;
    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;
    @Column(name = "from_consumer_account_id", nullable = false)
    private Long fromConsumerAccountId;
    @Column(name = "target_membership_id", nullable = false)
    private Long targetMembershipId;
    @Column(name = "proposed_team_version", nullable = false)
    private long proposedTeamVersion;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 16)
    private Status status;
    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "active_team_key")
    private Long activeTeamKey;

    protected WaitingRepresentativeTransferOffer() { }

    public static WaitingRepresentativeTransferOffer propose(long teamId, long fromAccountId,
            long targetMembershipId, long teamVersion, Instant proposedAt, Instant expiresAt) {
        WaitingRepresentativeTransferOffer value = new WaitingRepresentativeTransferOffer();
        value.waitingTeamId = positive(teamId, "teamId");
        value.fromConsumerAccountId = positive(fromAccountId, "fromAccountId");
        value.targetMembershipId = positive(targetMembershipId, "targetMembershipId");
        if (teamVersion < 0) throw new IllegalArgumentException("teamVersion must not be negative");
        value.proposedTeamVersion = teamVersion;
        value.proposedAt = nonNull(proposedAt, "proposedAt");
        value.expiresAt = nonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(proposedAt)) throw new IllegalArgumentException("expiresAt must be after proposedAt");
        value.status = Status.PROPOSED;
        value.activeTeamKey = teamId;
        return value;
    }

    public void accept(Instant now) { decide(Status.ACCEPTED, now); }
    public void reject(Instant now) { decide(Status.REJECTED, now); }
    public void revoke(Instant now) { decide(Status.REVOKED, now); }
    public void expire(Instant now) {
        if (now == null || now.isBefore(expiresAt)) throw new IllegalStateException("offer has not expired");
        decide(Status.EXPIRED, now);
    }

    public void requireAcceptable(Instant now) {
        requireProposed();
        if (now == null || now.isAfter(expiresAt)) throw new IllegalStateException("offer expired");
    }

    private void decide(Status next, Instant now) {
        requireProposed();
        decidedAt = nonNull(now, "now");
        if (now.isBefore(proposedAt)) throw new IllegalStateException("decision precedes proposal");
        status = next;
        activeTeamKey = null;
    }

    private void requireProposed() { if (status != Status.PROPOSED) throw new IllegalStateException("offer is terminal"); }

    public Long getId() { return id; }
    public Long getWaitingTeamId() { return waitingTeamId; }
    public Long getFromConsumerAccountId() { return fromConsumerAccountId; }
    public Long getTargetMembershipId() { return targetMembershipId; }
    public long getProposedTeamVersion() { return proposedTeamVersion; }
    public Status getStatus() { return status; }
    public Instant getProposedAt() { return proposedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Long getActiveTeamKey() { return activeTeamKey; }

    private static long positive(long value, String name) { if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static <T> T nonNull(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
}
