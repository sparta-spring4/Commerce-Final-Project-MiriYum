package com.miriyum.domain.platformoperator.entity.membersupport;

import com.miriyum.domain.auth.membersupport.MemberAccountType;
import com.miriyum.domain.auth.membersupport.MemberSanctionLevel;
import com.miriyum.domain.auth.membersupport.RestrictedFeature;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_sanctions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSanction extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_sanction_id")
    private Long id;
    @Column(name = "sanction_public_id", nullable = false, unique = true, length = 36)
    private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_support_case_id", nullable = false)
    private MemberSupportCase supportCase;
    @Column(name = "previous_sanction_id")
    private Long previousSanctionId;
    @Enumerated(EnumType.STRING) @Column(name = "account_type", nullable = false, length = 30)
    private MemberAccountType accountType;
    @Column(name = "account_id", nullable = false)
    private long accountId;
    @Enumerated(EnumType.STRING) @Column(name = "level", nullable = false, length = 40)
    private MemberSanctionLevel level;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 40)
    private MemberSanctionStatus status;
    @Column(name = "restricted_features", nullable = false, columnDefinition = "JSON")
    private String restrictedFeaturesJson;
    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;
    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion;
    @Column(name = "proposed_by_operator_id", nullable = false)
    private long proposedByOperatorId;
    @Column(name = "applied_by_operator_id")
    private Long appliedByOperatorId;
    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
    @Column(name = "ends_at")
    private LocalDateTime endsAt;
    @Column(name = "row_version", nullable = false)
    private long rowVersion = 1;

    public static MemberSanction propose(MemberSupportCase supportCase, MemberSanctionLevel level,
                                         Set<RestrictedFeature> features, String reasonCode, String policyVersion,
                                         long proposerId, LocalDateTime now) {
        MemberSanction sanction = new MemberSanction();
        sanction.publicId = UUID.randomUUID().toString();
        sanction.supportCase = supportCase;
        sanction.accountType = supportCase.getAccountType();
        sanction.accountId = supportCase.getAccountId();
        sanction.level = level;
        sanction.status = level == MemberSanctionLevel.PERMANENT_SUSPENSION
                ? MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL : MemberSanctionStatus.APPLIED;
        sanction.restrictedFeaturesJson = features.stream().map(Enum::name).sorted()
                .map(value -> "\"" + value + "\"").collect(Collectors.joining(",", "[", "]"));
        sanction.reasonCode = reasonCode;
        sanction.policyVersion = policyVersion;
        sanction.proposedByOperatorId = proposerId;
        sanction.proposedAt = now;
        if (sanction.status == MemberSanctionStatus.APPLIED) {
            sanction.appliedByOperatorId = proposerId;
            sanction.appliedAt = now;
            sanction.endsAt = switch (level) {
                case FEATURE_RESTRICTION -> now.plusDays(7);
                case TEMPORARY_SUSPENSION -> now.plusDays(30);
                default -> null;
            };
        }
        return sanction;
    }

    public void approvePermanent(long approverId, LocalDateTime now) {
        if (status != MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL) {
            throw new com.miriyum.global.exception.ServiceException(
                    com.miriyum.domain.auth.exception.AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        status = MemberSanctionStatus.APPLIED;
        appliedByOperatorId = approverId;
        appliedAt = now;
        endsAt = null;
        rowVersion++;
    }

    public void expire(LocalDateTime now) {
        if (status != MemberSanctionStatus.APPLIED || endsAt == null || endsAt.isAfter(now)) {
            throw new com.miriyum.global.exception.ServiceException(
                    com.miriyum.domain.auth.exception.AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
        }
        status = MemberSanctionStatus.EXPIRED;
        rowVersion++;
    }

    public static MemberSanction reducedRevision(MemberSupportCase appealCase, MemberSanction previous,
                                                 MemberSanctionLevel reducedLevel, Set<RestrictedFeature> features,
                                                 String reasonCode, long operatorId, LocalDateTime now) {
        MemberSanction revision = propose(
                appealCase, reducedLevel, features, reasonCode, previous.policyVersion, operatorId, now);
        revision.previousSanctionId = previous.id;
        return revision;
    }

    public static MemberSanction cancelledRevision(MemberSupportCase appealCase, MemberSanction previous,
                                                   String reasonCode, long operatorId, LocalDateTime now) {
        MemberSanction revision = propose(
                appealCase, previous.level, Set.of(), reasonCode, previous.policyVersion, operatorId, now);
        revision.previousSanctionId = previous.id;
        revision.status = MemberSanctionStatus.CANCELLED;
        revision.endsAt = now;
        return revision;
    }

    public void markReduced() {
        if (status != MemberSanctionStatus.APPLIED) conflict();
        status = MemberSanctionStatus.REDUCED;
        rowVersion++;
    }

    public void markCancelled() {
        if (status != MemberSanctionStatus.APPLIED) conflict();
        status = MemberSanctionStatus.CANCELLED;
        rowVersion++;
    }

    private static void conflict() {
        throw new com.miriyum.global.exception.ServiceException(
                com.miriyum.domain.auth.exception.AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
    }
}
