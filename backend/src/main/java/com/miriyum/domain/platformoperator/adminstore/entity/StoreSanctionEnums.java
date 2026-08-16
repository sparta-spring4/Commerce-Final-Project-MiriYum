package com.miriyum.domain.platformoperator.adminstore.entity;

public final class StoreSanctionEnums {
    private StoreSanctionEnums() {}

    public enum CaseStatus { SUBMITTED, ASSIGNED, PENDING_APPROVAL, ACTIVE, RESOLVED, REJECTED }
    public enum SanctionType { WARNING, FEATURE_RESTRICTION, TEMPORARY_SUSPENSION, PERMANENT_EXIT }
    public enum SanctionStatus { PENDING_APPROVAL, ACTIVE, RELEASED, EXPIRED, REJECTED }
    public enum ApprovalDecision { APPROVED, REJECTED }
}
