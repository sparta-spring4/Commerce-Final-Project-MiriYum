package com.miriyum.domain.reservation.entity;

import com.miriyum.domain.auth.qrepoch.ConsumerQrEpochSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 예약별 최신 opaque QR digest와 Auth epoch snapshot을 소유하는 current grant다. */
@Entity
@Table(
        name = "reservation_check_in_qr_grants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_check_in_qr_grants_reservation",
                        columnNames = "reservation_id"
                ),
                @UniqueConstraint(
                        name = "uk_reservation_check_in_qr_grants_digest",
                        columnNames = "token_digest"
                )
        }
)
public class ReservationCheckInQrGrant {

    private static final long TTL_SECONDS = 30L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_check_in_qr_grant_id")
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "token_digest", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] tokenDigest;

    @Column(name = "qr_epoch_account_id", nullable = false)
    private Long qrEpochAccountId;

    @Column(name = "qr_epoch_opaque_version", nullable = false, length = 46)
    private String qrEpochOpaqueVersion;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected ReservationCheckInQrGrant() {
    }

    private ReservationCheckInQrGrant(
            long reservationId,
            byte[] tokenDigest,
            ConsumerQrEpochSnapshot epochSnapshot,
            Instant issuedAt
    ) {
        this.reservationId = requirePositive(reservationId, "reservationId");
        this.tokenVersion = 1L;
        replaceCredential(tokenDigest, epochSnapshot, issuedAt);
    }

    /** 첫 current grant를 version 1로 발급한다. */
    public static ReservationCheckInQrGrant issue(
            long reservationId,
            byte[] tokenDigest,
            ConsumerQrEpochSnapshot epochSnapshot,
            Instant issuedAt
    ) {
        return new ReservationCheckInQrGrant(
                reservationId,
                tokenDigest,
                epochSnapshot,
                issuedAt
        );
    }

    /** 잠근 current 행의 credential을 무중첩 새 version으로 교체한다. */
    public void rotate(
            byte[] tokenDigest,
            ConsumerQrEpochSnapshot epochSnapshot,
            Instant issuedAt
    ) {
        if (tokenVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("tokenVersion is exhausted");
        }
        tokenVersion++;
        replaceCredential(tokenDigest, epochSnapshot, issuedAt);
    }

    /** digest 일치·30초 반개구간·미소비 조건을 함께 판정한다. */
    public boolean isUsable(byte[] candidateDigest, Instant now) {
        if (candidateDigest == null || candidateDigest.length != 32 || now == null) {
            return false;
        }
        return consumedAt == null
                && !now.isBefore(issuedAt)
                && now.isBefore(expiresAt)
                && MessageDigest.isEqual(tokenDigest, candidateDigest);
    }

    /** 유효한 current grant를 한 번 소비한다. */
    public void consume(Instant consumedAt) {
        if (this.consumedAt != null) {
            throw new IllegalStateException("grant is already consumed");
        }
        Instant timestamp = requireInstant(consumedAt, "consumedAt");
        if (timestamp.isBefore(issuedAt) || !timestamp.isBefore(expiresAt)) {
            throw new IllegalArgumentException("consumedAt must be inside the grant TTL");
        }
        this.consumedAt = timestamp;
    }

    public ConsumerQrEpochSnapshot epochSnapshot() {
        return new ConsumerQrEpochSnapshot(qrEpochAccountId, qrEpochOpaqueVersion);
    }

    private void replaceCredential(
            byte[] digest,
            ConsumerQrEpochSnapshot snapshot,
            Instant issueTime
    ) {
        this.tokenDigest = requireDigest(digest);
        if (snapshot == null) {
            throw new IllegalArgumentException("epochSnapshot must not be null");
        }
        this.qrEpochAccountId = snapshot.accountId();
        this.qrEpochOpaqueVersion = snapshot.opaqueVersion();
        this.issuedAt = requireInstant(issueTime, "issuedAt").truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = this.issuedAt.plusSeconds(TTL_SECONDS);
        this.consumedAt = null;
    }

    private static byte[] requireDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("tokenDigest must contain 32 bytes");
        }
        return digest.clone();
    }

    private static long requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Instant requireInstant(Instant value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public byte[] getTokenDigest() {
        return tokenDigest.clone();
    }

    public Long getQrEpochAccountId() {
        return qrEpochAccountId;
    }

    public String getQrEpochOpaqueVersion() {
        return qrEpochOpaqueVersion;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
