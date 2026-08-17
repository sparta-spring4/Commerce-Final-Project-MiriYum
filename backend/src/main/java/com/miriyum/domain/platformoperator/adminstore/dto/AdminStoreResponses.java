package com.miriyum.domain.platformoperator.adminstore.dto;

import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.CaseStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionStatus;
import com.miriyum.domain.platformoperator.adminstore.entity.StoreSanctionEnums.SanctionType;
import com.miriyum.domain.store.dto.administration.StoreAdministrationContracts.RestrictedFeature;
import com.miriyum.domain.store.enums.OperationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class AdminStoreResponses {
    private AdminStoreResponses() {}

    public record CaseData(String caseId, long storeId, String violationType,
                           Set<String> evidenceReferences, String policyVersion, CaseStatus status,
                           long caseVersion, long createdBy, Long assignedOperatorId, Instant createdAt) {}
    public record SanctionData(long sanctionId, String caseId, long storeId, SanctionType type,
                               SanctionStatus status, long sanctionVersion, long storeEnforcementVersion,
                               Set<RestrictedFeature> restrictedFeatures, Instant startsAt,
                               Instant endsAt, Instant createdAt) {}
    public record CaseDetail(String caseId, long storeId, String violationType,
                             Set<String> evidenceReferences, String policyVersion, CaseStatus status,
                             long caseVersion, long createdBy, Long assignedOperatorId, Instant createdAt,
                             List<SanctionData> sanctions) {}
    public record ImpactPreviewData(long previewId, String caseId, long storeId, long caseVersion,
                                    long storeEnforcementVersion, long confirmedReservationCount,
                                    long activeWaitingTeamCount, long confirmedPickupCount,
                                    long unsettledPaymentCount, String digest, Instant expiresAt) {}
    public record StoreSummary(long storeId, String name, long storeOperatorAccountId,
                               OperationStatus operationStatus, boolean reservationEnabled,
                               boolean menuHoldEnabled, boolean pickupEnabled, long enforcementVersion,
                               Set<SanctionType> activeSanctionTypes) {}
    public record StoreDetail(long storeId, String name, long storeOperatorAccountId,
                              OperationStatus operationStatus, boolean reservationEnabled,
                              boolean menuHoldEnabled, boolean pickupEnabled, long enforcementVersion,
                              Set<SanctionType> activeSanctionTypes, LocalDateTime createdAt,
                              List<String> openCaseIds) {}
    public record StorePage(List<StoreSummary> content, int page, int size,
                            long totalElements, int totalPages) {}
}
