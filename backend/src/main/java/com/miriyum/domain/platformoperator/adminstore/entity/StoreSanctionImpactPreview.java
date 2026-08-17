package com.miriyum.domain.platformoperator.adminstore.entity;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.ImpactPreviewData;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name = "store_sanction_impact_previews") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSanctionImpactPreview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_sanction_impact_preview_id") private Long id;
    @Column(name = "case_public_id", nullable = false, length = 36) private String caseId;
    @Column(name = "store_id", nullable = false) private long storeId;
    @Column(name = "case_version", nullable = false) private long caseVersion;
    @Column(name = "store_enforcement_version", nullable = false) private long storeEnforcementVersion;
    @Column(name = "confirmed_reservation_count", nullable = false) private long confirmedReservationCount;
    @Column(name = "active_waiting_team_count", nullable = false) private long activeWaitingTeamCount;
    @Column(name = "confirmed_pickup_count", nullable = false) private long confirmedPickupCount;
    @Column(name = "unsettled_payment_count", nullable = false) private long unsettledPaymentCount;
    @Column(name = "shape_fingerprint", nullable = false, length = 64) private String shapeFingerprint;
    @Column(name = "digest", nullable = false, length = 64) private String digest;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    public static StoreSanctionImpactPreview create(String caseId, long storeId, long caseVersion,
            long enforcementVersion, long reservations, long waiting, long pickups, long payments,
            String shapeFingerprint, String digest, Instant expiresAt) {
        StoreSanctionImpactPreview value = new StoreSanctionImpactPreview();
        value.caseId=caseId; value.storeId=storeId; value.caseVersion=caseVersion;
        value.storeEnforcementVersion=enforcementVersion; value.confirmedReservationCount=reservations;
        value.activeWaitingTeamCount=waiting; value.confirmedPickupCount=pickups;
        value.unsettledPaymentCount=payments; value.shapeFingerprint=shapeFingerprint;
        value.digest=digest; value.expiresAt=expiresAt; return value;
    }
    public boolean matches(long storeId, String caseId, long caseVersion, long enforcementVersion,
                           String shapeFingerprint, String digest, Instant now) {
        return this.storeId==storeId && this.caseId.equals(caseId) && this.caseVersion==caseVersion
                && this.storeEnforcementVersion==enforcementVersion
                && this.shapeFingerprint.equals(shapeFingerprint) && this.digest.equals(digest)
                && expiresAt.isAfter(now);
    }
    public ImpactPreviewData data() { return new ImpactPreviewData(id, caseId, storeId, caseVersion,
            storeEnforcementVersion, confirmedReservationCount, activeWaitingTeamCount,
            confirmedPickupCount, unsettledPaymentCount, digest, expiresAt); }
}
