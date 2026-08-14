package com.miriyum.domain.platformoperator.entity;

import com.miriyum.domain.platformoperator.enums.AdminCaseAssignmentStatus;
import com.miriyum.domain.platformoperator.enums.AdminCaseType;
import com.miriyum.global.entity.BaseEntity;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "admin_case_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminCaseAssignment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "admin_case_assignment_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_type", nullable = false, length = 50)
    private AdminCaseType caseType;

    @Column(name = "case_id", nullable = false, length = 100)
    private String caseId;

    @Column(name = "case_version", nullable = false)
    private long caseVersion;

    @Column(name = "platform_operator_account_id", nullable = false)
    private Long platformOperatorAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdminCaseAssignmentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public static AdminCaseAssignment assign(
            AdminCaseType caseType,
            String caseId,
            long caseVersion,
            Long operatorId,
            Instant expiresAt,
            Instant assignedAt
    ) {
        if (caseVersion < 1 || caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("valid case id and version are required");
        }
        Instant assigned = Objects.requireNonNull(assignedAt);
        Instant expiry = Objects.requireNonNull(expiresAt);
        if (!expiry.isAfter(assigned)) {
            throw new IllegalArgumentException("assignment expiry must follow assignment time");
        }
        AdminCaseAssignment assignment = new AdminCaseAssignment();
        assignment.caseType = Objects.requireNonNull(caseType);
        assignment.caseId = caseId;
        assignment.caseVersion = caseVersion;
        assignment.platformOperatorAccountId = Objects.requireNonNull(operatorId);
        assignment.status = AdminCaseAssignmentStatus.ASSIGNED;
        assignment.expiresAt = expiry;
        return assignment;
    }

    public boolean isActiveAt(Instant now) {
        return status == AdminCaseAssignmentStatus.ASSIGNED && Objects.requireNonNull(now).isBefore(expiresAt);
    }

    public void reassign(long operatorId, Instant newExpiresAt, Instant now) {
        Instant changedAt = Objects.requireNonNull(now);
        Instant expiry = Objects.requireNonNull(newExpiresAt);
        if (operatorId < 1 || !expiry.isAfter(changedAt) || status == AdminCaseAssignmentStatus.CLOSED) {
            throw new IllegalArgumentException("valid active reassignment is required");
        }
        platformOperatorAccountId = operatorId;
        expiresAt = expiry;
        status = AdminCaseAssignmentStatus.ASSIGNED;
    }

    public void close() {
        status = AdminCaseAssignmentStatus.CLOSED;
    }
}
