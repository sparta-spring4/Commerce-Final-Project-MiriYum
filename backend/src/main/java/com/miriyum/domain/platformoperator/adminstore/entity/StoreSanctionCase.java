package com.miriyum.domain.platformoperator.adminstore.entity;

import com.miriyum.domain.platformoperator.adminstore.dto.AdminStoreResponses.CaseData;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.CaseStatus;
import com.miriyum.domain.platformoperator.adminstore.exception.AdminStoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "store_sanction_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreSanctionCase extends com.miriyum.global.entity.BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_sanction_case_id") private Long id;
    @Column(name = "case_public_id", nullable = false, unique = true, length = 36) private String publicId;
    @Column(name = "store_id", nullable = false) private long storeId;
    @Column(name = "created_by", nullable = false) private long createdBy;
    @Column(name = "assigned_operator_id") private Long assignedOperatorId;
    @Column(name = "violation_type", nullable = false, length = 50) private String violationType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_references", nullable = false, columnDefinition = "json")
    private Set<String> evidenceReferences;
    @Column(name = "policy_version", nullable = false, length = 50) private String policyVersion;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private CaseStatus status;
    @Column(name = "case_version", nullable = false) private long caseVersion;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;

    public static StoreSanctionCase create(long storeId, long createdBy, String violationType,
                                           Set<String> evidenceReferences, String policyVersion,
                                           Instant now) {
        StoreSanctionCase value = new StoreSanctionCase();
        value.publicId = UUID.randomUUID().toString(); value.storeId = storeId;
        value.createdBy = createdBy; value.violationType = violationType;
        value.evidenceReferences = Set.copyOf(evidenceReferences); value.policyVersion = policyVersion;
        value.status = CaseStatus.SUBMITTED; value.caseVersion = 1; value.submittedAt = now;
        return value;
    }

    public void assign(long operatorId, long expectedVersion) {
        if (status != CaseStatus.SUBMITTED || caseVersion != expectedVersion) {
            throw new ServiceException(AdminStoreErrorCode.STORE_CASE_STATE_CONFLICT);
        }
        assignedOperatorId = operatorId; status = CaseStatus.ASSIGNED; caseVersion++;
    }

    public void pendingApproval() { status = CaseStatus.PENDING_APPROVAL; }
    public void activate() { status = CaseStatus.ACTIVE; }
    public void resolve() { status = CaseStatus.RESOLVED; }

    public CaseData data() {
        return new CaseData(publicId, storeId, violationType, evidenceReferences, policyVersion,
                status, caseVersion, createdBy, assignedOperatorId, submittedAt);
    }
}
