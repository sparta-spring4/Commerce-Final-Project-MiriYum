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

/** 원문 초대 코드 없이 일회성 일행 초대의 수명주기를 보존한다. */
@Entity
@Table(name = "waiting_party_invitations", uniqueConstraints = @UniqueConstraint(
        name = "uk_waiting_party_invitations_token_hash", columnNames = "token_hash"))
public class WaitingPartyInvitation {

    public enum Status { ISSUED, ACCEPTED, REVOKED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "waiting_party_invitation_id")
    private Long id;
    @Column(name = "waiting_team_id", nullable = false)
    private Long waitingTeamId;
    @Column(name = "inviter_consumer_account_id", nullable = false)
    private Long inviterConsumerAccountId;
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;
    @Column(name = "issued_team_version", nullable = false)
    private long issuedTeamVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;
    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "accepted_by_consumer_account_id")
    private Long acceptedByConsumerAccountId;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected WaitingPartyInvitation() { }

    public static WaitingPartyInvitation issue(long teamId, long inviterId, String tokenHash,
            long teamVersion, Instant issuedAt, Instant expiresAt) {
        WaitingPartyInvitation value = new WaitingPartyInvitation();
        value.waitingTeamId = positive(teamId, "teamId");
        value.inviterConsumerAccountId = positive(inviterId, "inviterId");
        value.tokenHash = fixedHex(tokenHash);
        if (teamVersion < 0) throw new IllegalArgumentException("teamVersion must not be negative");
        value.issuedTeamVersion = teamVersion;
        value.issuedAt = nonNull(issuedAt, "issuedAt");
        value.expiresAt = nonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be after issuedAt");
        value.status = Status.ISSUED;
        return value;
    }

    public void accept(long consumerAccountId, Instant now) {
        requireUsable(now);
        acceptedByConsumerAccountId = positive(consumerAccountId, "consumerAccountId");
        acceptedAt = now;
        status = Status.ACCEPTED;
    }

    public void revoke(Instant now) {
        requireIssued();
        revokedAt = nonNull(now, "now");
        if (now.isBefore(issuedAt)) throw new IllegalStateException("revocation precedes issue");
        status = Status.REVOKED;
    }

    public void expire(Instant now) {
        requireIssued();
        if (now == null || now.isBefore(expiresAt)) throw new IllegalStateException("invitation has not expired");
        status = Status.EXPIRED;
    }

    public void requireUsable(Instant now) {
        requireIssued();
        if (now == null || now.isAfter(expiresAt)) throw new IllegalStateException("invitation expired");
    }

    private void requireIssued() {
        if (status != Status.ISSUED) throw new IllegalStateException("invitation is terminal");
    }

    public Long getId() { return id; }
    public Long getWaitingTeamId() { return waitingTeamId; }
    public Long getInviterConsumerAccountId() { return inviterConsumerAccountId; }
    public String getTokenHash() { return tokenHash; }
    public long getIssuedTeamVersion() { return issuedTeamVersion; }
    public Status getStatus() { return status; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getAcceptedByConsumerAccountId() { return acceptedByConsumerAccountId; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    private static String fixedHex(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("tokenHash must be lowercase SHA-256");
        return value;
    }
    private static long positive(long value, String name) { if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static <T> T nonNull(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
}
